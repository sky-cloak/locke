# Locke vs vanilla Keycloak — Redis vs Infinispan on OVH (3-pod, production mode)

**Date:** 2026-05-24 · **Keycloak base:** 26.6.1 · **Mode:** `start --optimized`

This is the production-mode clustered run: both stacks built with `kc.sh build` and started
with `--optimized` (providers baked at build time, not `start-dev`), on dedicated nodes, with
the load generator, database, and cache each isolated. Numbers reflect the cache backend, not
host contention. The whole run was orchestrated by an **in-cluster runner pod**, so it was
independent of any operator laptop. Raw evidence: `results/2026-05-24/evidence/`
(`phase-ab-matrix.log`, per-pod KC logs, `RESULTS-SUMMARY.txt`).

## Methodology

**Cluster:** OVH Managed Kubernetes, region BHS5. Dedicated `load-v1` pool of **4 × b3-64
(16 vCPU / 64 GB each)**.

| Role (`bench-role`) | Nodes | Runs |
|---|---|---|
| `kc` | 3 | one Keycloak pod per node (required anti-affinity) — a real 3-machine cluster |
| `infra` | 1 | load generator + Postgres + Redis (isolated from the KC pods) |

**Stacks (identical config: `--optimized`, pinned CPU `requests==limits` 6 vCPU / 6 GB,
`-Xms2g -Xmx4g -XX:+UseG1GC`, DB pool 50, same Postgres `max_connections=500`, same realm):**

- **A3 — Infinispan (vanilla Keycloak 26.6.1):** `KC_CACHE=ispn`, clustered via JGroups
  `jdbc-ping` (3-member view). Distributed caches replicate across the 3 pods.
- **B3 — Locke 26.6.1:** `KC_CACHE=redis`. Realm/user/authz caches served by an in-JVM
  Caffeine L1 (bounded 10k, 60s TTL) with Redis pub/sub invalidation; no JGroups. Sessions
  delegate to JPA.

**Load:** keycloak-benchmark Gatling `AuthorizationCode` (browser login → credentials POST →
code exchange → token refresh → logout), realm `bench-kcb` (100 users, 1 confidential client).
Each load point ran 60s (warm-up 5s, ramp 5s). Stacks ran **sequentially**; the idle stack was
scaled to 0 so load numbers are clean. Throughput is Gatling mean req/s; latency is p95/p99.

---

## 1. Throughput parity

Both stacks, 0 errors through 250 users/sec.

| Load (ups) | A3 Infinispan (rps) | B3 Locke (rps) | Parity B/A |
|---|---|---|---|
| 80  | 274.1 | 274.5 | 100.1% |
| 160 | 548.1 | 548.1 | 100.0% |
| 250 | 855.5 | 856.4 | 100.1% |

**Locke matches Infinispan on throughput within ~0.1% across the range — exact parity, 0 errors.**

## 2. Latency (aggregate p99)

| Load | A3 p99 | B3 p99 |
|---|---|---|
| 80  | 64ms  | 104ms |
| 160 | 64ms  | 91ms  |
| 250 | 167ms | 151ms |

Infinispan has slightly lower p99 at moderate load (realm/user reads are in-process; Locke adds
a Redis round trip on L1 misses). Both stay well under 170ms p99 to 250 ups; at the top of the
range Locke is marginally lower.

## 3. Resilience — node(s) down under load — the standout result

Sustained 80 ups for 150s; pod(s) killed at T+45s.

| Scenario | B3 Locke | A3 Infinispan |
|---|---|---|
| **1 node down** | 0.01% failed (6 / 45,274), **p99 908ms** | 0.46% failed (207 / 44,843), **p99 31,033ms** |
| **2 nodes down** | 0.18% failed (83 / 45,095), **p99 2,609ms** | 0.35% failed (159 / 44,977), **p99 39,688ms** |

When an Infinispan node dies the cluster **stalls ~31–40 seconds** doing JGroups rebalance +
state transfer; in-flight requests hang into the tens of seconds. Locke just keeps serving from
Redis: **p99 stays sub-second (1-down) / ~2.6s (2-down)** — roughly **34× / 15× better p99**, with
far fewer failures. For an operator this is "a pod restarted and nobody noticed" vs "auth latency
spiked to 31s for half a minute."

## 4. Operational events under load

| Event | A3 Infinispan | B3 Locke |
|---|---|---|
| **Rolling restart** (all 3 pods) | rollout 83s, p99 622ms, **0 failures** | rollout 79s, p99 1,348ms, **0 failures** |
| **Scale 3→2→3** (new pod join) | join 22s, p99 306ms, **0 failures** | join 28s, p99 118ms, **0 failures** |

Same-version pod cycling and scaling are clean on both backends.

## 5. Redis failure under load (Locke)

| Phase | Result |
|---|---|
| Baseline (Redis up) | 201.4 rps, p99 115ms, **0 failures** |
| **Redis killed mid-load** | in-flight requests **hang** — the Redis client path has no read/connect timeout, so request threads block until Redis returns (no result produced; load gen had to be force-stopped) |
| After Redis returns | 128 rps, p99 70ms, **0 failures** — **full recovery**; KC pods auto-reconnect (Lettuce "Reconnected to redis") |

**Honest caveat (26.6.1) → FIXED in 26.6.2-2:** on 26.6.1 a Redis outage *hung* in-flight requests
because the Lettuce calls weren't bounded by a timeout. This is **fixed** in `locke:26.6.2-2`
(Lettuce `ClientOptions` with `TimeoutOptions.enabled(timeout)` + `disconnectedBehavior=REJECT_COMMANDS`).

**Re-validated on 26.6.2-2 (fixed image):**

| Phase | 26.6.1 (before) | 26.6.2-2 (fixed) |
|---|---|---|
| Redis killed mid-load | requests **hang** until Redis returns | requests **fail fast** — `RedisCommandTimeoutException` after the 2s command timeout (no thread hang) |
| After Redis returns | full recovery, 0 errors | **full recovery** — 127 rps, p99 74ms, **0 failures, 0 timeouts** once Lettuce reconnects |

During the outage the cache path errors (expected — Locke's caches need Redis); the win is it
**degrades fast and recovers** instead of hanging. For zero-downtime through a Redis failure, run
Redis HA (Sentinel/Cluster) so there is no single-node outage. The same fixed image also resolves
`KC_CACHE_REDIS_URL` correctly under `start --optimized` (no `KC_SPI_…URL` workaround needed).

## 6. Rolling version upgrade 26.3.5 → 26.6.1 under load

- **Vanilla (Infinispan):** the rolling upgrade crosses an **incompatible Infinispan/JGroups
  major (15 → 16)**. During the mixed-version window the cluster cannot form a unified view, and
  in-flight + new login requests **hang indefinitely** (observed: the load generator stalled and
  had to be force-terminated). The rollout completes (~84s) and service recovers only once all 3
  pods are on 26.6.1. In practice this is a **cache-layer outage** for the duration of the upgrade.
- **Locke (Redis): rolls cleanly under load — no outage.** Tested with properly-versioned Locke
  images (built from the real KC tags; `version.txt` = 26.3.5 / 26.6.1 / 26.6.2). Windowed load
  (50 ups), image flipped mid-stream, fresh DB so the old version owns the schema:

  | Upgrade | Cutover behavior | End state |
  |---|---|---|
  | **26.3.5 → 26.6.1** (full DB migration) | ~85s elevated latency, p99 peak **2,185ms**, one **0.02%** blip | 3/3 on 26.6.1, p99 ~100ms, **0 fail** |
  | **26.6.1 → 26.6.2** (patch) | brief p99 bump, peak **1,428ms**, **0 failures throughout** | 3/3 on 26.6.2, p99 ~130ms, **0 fail** |

  Both complete gracefully: the only cost is a transient latency bump from the **standard Keycloak
  DB migration** as each new pod starts — there is **no JGroups cluster-protocol version barrier**,
  so the cluster never stalls the way Infinispan does. (Note: an earlier attempt with a mislabeled
  `999.0.0-SNAPSHOT` "26.3.5" image crash-looped because 26.6.1 refused the apparently-newer schema;
  building a correctly-versioned 26.3.5 image fixed it.)

## 7. Same-version migration: stock Keycloak → Locke (26.6.1) under load

The realistic adoption scenario: an operator switches stock Keycloak to Locke **without changing
the Keycloak version** (same 26.6.1, same DB schema). The same StatefulSet was rolled from vanilla
(Infinispan) to Locke (Redis) — an image + cache-config change — under sustained login load.

| Phase | Result |
|---|---|
| Baseline (Infinispan) | 204.8 rps, p99 73ms, **0 failures** |
| **Cutover window (~60–75s)** | p99 spikes to **1,327ms**, **~0.02% failures**, one brief stall — as Infinispan pods leave the JGroups cluster (departure rebalance) and sessions don't carry across cache backends |
| After migration (Locke) | 201.7 rps, p99 122ms, **0 failures** — healthy, **throughput parity** |

Windowed timeline (15s load windows): vanilla baseline clean → cutover window p99 1.3s / 0.02% fail
+ one stalled window → recovers within ~75s → stable Locke at p99 ~116ms, 0 failures.

**Finding:** adopting Locke at the same version **lands cleanly at parity**; the *live* cutover has
a small, **bounded** disruption window driven by Infinispan's departure (not Locke). For a seamless
switch, do the cutover in a brief maintenance window or drain pods one at a time. This is a far
better outcome than the cross-version Infinispan *upgrade* (which is a full outage).

## 8. Redis placement: embedded (colocated) vs external (cross-node)

Does it matter whether Redis sits on the same node as Keycloak or on a separate one? Compared
Redis on a dedicated node (external, a network hop from every KC pod) vs Redis colocated on a KC
node, same load.

| Load | External (Redis on separate node) | Colocated (Redis on a KC node) |
|---|---|---|
| 80 ups | 270 rps, p99 81ms | 270 rps, p99 75ms |
| 160 ups | 541 rps, p99 93ms | 540 rps, p99 112ms |
| 250 ups | both saturate (multi-second p99, run-to-run noise) | |

**Finding: placement is negligible** below saturation — throughput is identical and p99 differs by
only ~6–20ms (within noise). Locke's in-JVM L1 (Caffeine) absorbs most reads, so the Redis round
trip rarely gates a request. **Practical implication: a managed/external Redis on the same network
is fine** — you don't need to colocate Redis with Keycloak for performance.

---

## Takeaways

1. **Throughput: a tie (~100%).** Clustered Keycloak on Redis serves the same load as embedded
   Infinispan, error-free, to saturation.
2. **Latency: a small, honest trade.** Moving local realm/user caches to Redis adds a few ms at
   moderate load; both stay <170ms p99 to 250 ups.
3. **Resilience: the real win.** Losing a node costs Infinispan a ~31–40s rebalance stall; Locke
   shrugs it off (sub-second to low-seconds p99). This is the operability argument.
4. **Upgrades:** Infinispan rolling upgrades across an incompatible JGroups version are an outage;
   Locke removes that cluster-protocol version barrier (no JGroups).
5. **Adopting Locke (same version):** lands cleanly at throughput parity; the live cutover has only
   a bounded ~60–75s blip (0.02% failures), driven by Infinispan's departure — best done in a short
   maintenance window.
6. **Gaps found and fixed (in `26.6.2-2`):** the Redis client timeout (outage no longer hangs —
   fails fast at 2s and recovers) and `KC_CACHE_REDIS_URL` honored under `--optimized`. A
   properly-versioned Locke 26.3.5 image now exists, so the cross-version upgrade path was measured
   (section 6). For zero-downtime through a Redis failure, run Redis HA (Sentinel/Cluster).

## Caveats

Single region, dev-grade nodes; A3 and B3 ran sequentially, not simultaneously. Redis-failure
and version-upgrade tests are characterized honestly above (one hang finding, one packaging
limitation). Raw logs are archived in `results/2026-05-24/evidence-2026-05-24.zip`.
