# Locke benchmark — Redis vs Infinispan (OVH · Azure · AWS)

Locke is a drop-in Keycloak distribution that moves the realm/user/authorization caches off
embedded Infinispan onto Redis (`KC_CACHE=redis`). This is the consolidated benchmark across
**three independent cloud runs**, all using the same harness — keycloak-benchmark Gatling
`AuthorizationCode` (full browser login → code exchange → token refresh → logout), realm
`bench-kcb`, 3 KC pods (anti-affinity, one per node), `start --optimized`, shared Postgres, identical
config; only the cache backend differs. Stacks run sequentially (idle stack scaled to 0). The
load only authenticates against a fixed user set — it does **not** continuously create users.

| Run | Date | Rig | Adds |
|---|---|---|---|
| **OVH** | 2026-05-24 | OVH MKS, 4× b3-64 (16 vCPU) | The foundational run: parity, resilience, upgrades, live migration, Redis-failure, placement |
| **Azure** | 2026-06-16 | AKS, E16as_v7 (AMD) | Reproduced parity + resilience on a 2nd cloud with the shipping image; rig-size lesson |
| **AWS** | 2026-06-20 | EKS c6i (Intel) + ElastiCache | The managed-Redis verdict: ElastiCache vs Azure Managed Redis; full latency distributions |

Visual report (charts, full distributions): **[report.html](./report.html)**. The local single-host
engineering journal is separate: [../RESULTS.md](../RESULTS.md).

---

## TL;DR

1. **Throughput: parity (~100%).** On all three clouds Locke matches Infinispan within ~0.1% to
   saturation, 0 errors.
2. **Resilience: the win.** Losing a KC pod costs Infinispan a **31–40 second** JGroups
   rebalance/state-transfer stall; Locke serves straight through — **sub-second** p99 (≈34× better
   on OVH, ≈340× on Azure). This is the reason Locke exists.
3. **Where to run Redis.** Co-located is best; **AWS ElastiCache (managed OSS cluster) is viable**;
   **avoid single-proxy managed Redis (Azure Managed Redis)** for this workload; don't keep
   Infinispan if you need HA.
4. **Honest trade:** a Redis round-trip per cache miss adds a few ms of latency vs an in-process
   cache. Both stay well under 170ms p99 to 250 logins/sec on adequate hardware.

---

## 1. Resilience — node loss under load (the headline)

Sustained 80 logins/sec for 150s; one (or two) KC pods killed at T+45s.

| Run / scenario | Locke p99 (failed) | Infinispan p99 (failed) | Factor |
|---|---|---|---|
| **OVH** — 1 node down | **908 ms** (0.01%) | 31,033 ms (0.46%) | ~34× |
| **OVH** — 2 nodes down | 2,609 ms (0.18%) | 39,688 ms (0.35%) | ~15× |
| **Azure** (E16) — 1 node down | **110 ms** (0.01%) | 37,511 ms (0.68%) | ~340× |
| **AWS ElastiCache** — 1 node down | **522 ms** (0%) | — | — |
| **Azure** — co-located redis | 268 ms (0.01%) | — | — |
| **Azure** — 6-node cluster | 53 ms | — | — |

When an Infinispan node dies the cluster must re-form its JGroups view and transfer state —
in-flight and new logins hang into the **tens of seconds**. Locke holds cache state in Redis, so a
lost KC pod is stateless and a non-event: surviving pods keep reading from Redis. For an operator
that's "a pod restarted and nobody noticed" vs "auth latency spiked to 31s for half a minute."

## 2. Throughput parity

Both stacks, 0 errors through 250 logins/sec, on every cloud.

| ups | OVH I / L (rps) | Azure I / L (rps) | AWS I / L-ElastiCache (rps) |
|---|---|---|---|
| 80  | 274.1 / 274.5 | 274.3 / 273.2 | 273.4 / 273.7 |
| 160 | 548.1 / 548.1 | 547.5 / 546.0 | 548.1 / 547.6 |
| 250 | 855.5 / 856.4 | 856.0 / 855.7 | rig-capped* |

`I` = Infinispan, `L` = Locke. **Parity within ~0.1%.** (*AWS 250 ups is rig-capped — see §8.)

## 3. Latency & full distributions

At 80/160 ups every backend scales linearly; the difference is latency. The full p50/mean/p95/p99/max
distributions for every backend are in **[report.html](./report.html)** (raw data:
`results/aws/fullstats.csv`, `results/full-distribution/*.csv`). The honest summary:

- **In-process Infinispan** is the latency floor (~5ms p50, <130ms p99) — it does cache ops as
  in-JVM method calls, zero network.
- **Locke** adds a Redis round-trip per L1 miss: OVH p99 104/91/151ms, Azure 78/80/145ms — a few ms
  more, error-free, still sub-170ms to 250 ups on adequate hardware.

Latency is **node-size-bound, not a Locke limit**: a Redis round-trip costs more per-login CPU than an
in-process hit, so undersized pods knee earlier (Azure: 160-ups Locke p99 was 1,210ms on small E8
pods → 80ms on E16/8-vCPU pods). Give Locke adequate per-login CPU headroom.

## 4. Where to run Redis — the managed-Redis verdict

Managed Redis is **not** all the same; what matters is the data path.

| Backend | Architecture | p99 @ 80 ups | Pod-loss p99 | Verdict |
|---|---|---|---|---|
| Co-located 6-node cluster (Azure) | Same-VM, 3+3 shards | 60 ms | 53 ms | **best** |
| Co-located single redis (Azure) | Same-VM, 1 node | 50 ms | 268 ms | resilient |
| **AWS ElastiCache** | Managed OSS, direct-to-shard | **181 ms** | 522 ms | **viable, +latency** |
| Azure Managed Redis | Managed, single proxy endpoint | **4,940 ms** | — | **avoid (proxy)** |

- **ElastiCache cluster-mode and co-located Redis are direct-to-shard OSS Redis** — Locke's Lettuce
  client opens one connection per shard and talks straight to it. ElastiCache is a perfectly viable
  Locke backend: p99 181ms at 80 ups, 522ms through a pod kill with zero errors.
- **Azure Managed Redis funnels all traffic through a single managed proxy endpoint.** Per operation
  it's fine (~0.6ms), but the proxy amplifies badly under the concurrency of ~850 logins/sec each
  doing several Redis round-trips: p99 **4,940ms** at 80 ups (~27× worse than ElastiCache), saturating
  to 601 rps / 18.6s at 250 ups. Its OSS clustering policy also rejects `READONLY` and its
  IP-discovered shards fail TLS hostname checks — Locke pins Redisson `readMode=MASTER` to connect at
  all, but the proxy throughput ceiling remains.
- **Practical guidance:** co-locate Redis for best latency; an OSS cluster-mode managed service
  (ElastiCache, or equivalent) you talk to directly is fine — budget the network hop and size the
  tier; avoid single-proxy-endpoint managed Redis for this write-heavy workload. Full deployment
  guidance: [../../docs/redis-backend-guide.md](../../docs/redis-backend-guide.md).

## 5. What we tried that didn't work (refuted experiments)

When Azure Managed Redis came out slow, two Locke-side fixes were prototyped to rescue it. The
benchmark **killed both** — a useful, honest result that kept them out of the shipping image:

- **Ownership-gated auth-session L1 cache:** *worse* — p99 4,882ms vs 281ms at 80 ups. Authentication
  is write-heavy and the per-write pub/sub invalidation floods the Netty event loop.
- **Shared-connection / connection-ring model** (replacing the borrow-block pool): *worse* — 435 rps /
  31.8s and 423 rps / 43.7s respectively, vs the pool's 616 rps / 13.4s. The pool's blocking checkout
  turns out to be useful **backpressure** that protects a capacity-limited proxy; removing it made
  things worse.

The root problem was the proxy, not Locke's client. **What did ship** (validated): Redisson
`readMode`/`subscriptionMode=MASTER` (so AMR-OSS connects at all), a tunable connection-pool size and
cluster topology-refresh cadence, and the parameterized benchmark harness used for these runs.

## 6. Cross-version upgrades & live migration (OVH)

- **Rolling version upgrade under load, Locke (Redis):** rolls cleanly — no JGroups version handshake
  to stall on. 26.3.5 → 26.6.1 (full DB migration): ~85s of elevated latency, p99 peak 2,185ms, one
  0.02% blip; 26.6.1 → 26.6.2 (patch): p99 peak 1,428ms, 0 failures. The only cost is the standard
  Keycloak DB migration as each new pod starts. Vanilla Infinispan across an incompatible
  Infinispan/JGroups major (15→16) cannot form a unified view mid-window and hangs — but a
  mixed-version rolling cluster across that boundary is *not* the operation upstream recommends; the
  recommended path is a brief planned restart. So this is "one fewer constraint," not a blanket
  no-downtime promise.
- **Same-version adoption (stock Keycloak → Locke, both 26.6.1):** lands cleanly at throughput parity.
  The live cutover has a bounded ~60–75s blip (p99 1,327ms, ~0.02% failures) driven by Infinispan's
  *departure* from the JGroups cluster, not by Locke. For a seamless switch, cut over in a short
  maintenance window or drain pods one at a time.

## 7. Redis failure & placement (OVH)

- **Redis outage:** on 26.6.1 a Redis outage *hung* in-flight requests (no client timeout). **Fixed in
  26.6.2-2** (Lettuce `TimeoutOptions` + `disconnectedBehavior=REJECT_COMMANDS`): the cache path now
  **fails fast** at the 2s command timeout and fully recovers when Redis returns (auto-reconnect). For
  zero-downtime *through* a Redis failure, run Redis HA (Sentinel/Cluster).
- **Placement:** Redis on a dedicated node vs co-located on a KC node differs by only ~6–20ms p99
  below saturation — **negligible**. The in-JVM L1 absorbs most reads. A managed/external Redis on the
  same network is fine for performance (subject to §4's architecture caveat).

## 8. Methodology & apples-to-apples caveats

The three rigs are not identical, so backend conclusions are only drawn where the rig isn't the
bottleneck.

- **Different CPUs across clouds.** All x86-64 with matched vCPU counts and the identical amd64 images,
  but different silicon: OVH b3-64, **Azure E-series `as_v7` = AMD EPYC**, **AWS `c6i` = Intel Xeon
  (Ice Lake)**. Cross-cloud *absolute* latencies carry a CPU-vendor difference; the clean same-CPU
  backend comparison is the AWS-internal set (ElastiCache vs co-located vs Infinispan) at 80/160 ups.
- **The AWS 250-ups cliff is a rig artifact, not a backend property.** On the AWS rig, Postgres shared
  one node with the loadgen/infra tier and its write-ahead log sat on EBS gp3; at 250 ups (~850
  session-persist writes/sec) *every* backend kneed — including in-process Infinispan (760 rps, p99
  4.6s), which touches no Redis. On the Azure rig, with Postgres on its own node, the identical
  software held 250 ups at ~850 rps / 91ms. Same code, different storage IOPS. We therefore compare
  backends at 80/160 ups.
- **Topologies differ in size.** "Co-located single redis" is one Redis thread; ElastiCache here is a
  3-shard cluster; the Azure cluster is 6 nodes. A bigger deployment naturally absorbs more — it's a
  "what you'd actually deploy" comparison, not a same-size topology shoot-out.
- **Single 60s runs per point** — directional, not SLOs. Resilience and the big-rig parity are the
  cleanest signals; latency multiples shift run-to-run.

---

## Takeaways

1. **Throughput is a tie (~100%)** on three clouds — clustered Keycloak on Redis serves the same load
   as embedded Infinispan, error-free, to saturation.
2. **Resilience is the real win.** Node loss costs Infinispan a 31–40s JGroups stall; Locke shrugs it
   off (sub-second). This is the operability argument and the reason to adopt.
3. **Latency is a small, honest trade** — a few ms for the Redis round-trip, sub-170ms p99 to 250 ups
   on adequate hardware.
4. **Run Redis co-located, or on a direct-to-shard OSS managed service** (ElastiCache). Avoid
   single-proxy managed Redis (Azure Managed Redis) for this workload. See
   [../../docs/redis-backend-guide.md](../../docs/redis-backend-guide.md).
5. **Upgrades & migration:** Locke removes the JGroups version barrier; cross-version rolling upgrades
   and same-version adoption land cleanly, with only the standard DB-migration latency bump.

*Raw evidence under `results/`. Visual report: [report.html](./report.html).*
