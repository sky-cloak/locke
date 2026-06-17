# Locke resilience benchmark, Prometheus-instrumented (plan)

Status: plan. Reproduces and extends the published OVH run ([REPORT.md](./REPORT.md)) as a
fully instrumented resilience benchmark of Locke (Redis) vs upstream Keycloak (Infinispan),
so every resilience claim in the blog post maps to a captured metric. Designed against the
real harness under `benchmark/k8s-ovh/` and `benchmark/compose/`, then adversarially audited
for measurement gaps and overclaim risk; the corrections from that audit are folded in below.

## The contract

Every headline claim in the blog must map to a captured signal. Two sources, time-aligned:

- **Client-side (source of truth for latency/throughput):** Gatling `KCBRESULT ups= rps= p95= p99= failed=%` from `run-kcb.sh`, exactly as the prior report.
- **Server-side (corroboration + the failure-mode story):** Prometheus scraping KC native metrics, Locke's `keycloak_redis_*`, `redis_exporter`, `node_exporter`, `kube-state-metrics`.

If a claim has no metric, it does not go in the post.

## Base version: KC 26.6.3 on both stacks (do not repeat the skew)

The prior rig compared vanilla **26.6.1** against Locke **26.6.2-2**. That confounds the
steady-state latency numbers with a patch-level delta. Both stacks are rebuilt on the same KC
base:

| Stack | Image | Cache |
|---|---|---|
| A (control) | stock Keycloak **26.6.3** | embedded Infinispan, `KC_CACHE_STACK=jdbc-ping` |
| B (Locke) | Locke **26.6.3 line, this session's build** (cluster-write fix + `cache-redis-timeout` fix + HA-topology work) | Redis |

This is the point of "use 26.6.3-1 as base": those fixes are currently uncommitted on top of
`main@26.6.3-1`. They must be committed and the Locke image rebuilt (it becomes the next Locke
build on the 26.6.3 base, i.e. `26.6.3-2`) before the benchmark is meaningful, because the
cluster and timeout scenarios below exercise exactly those fixes.

## Topology (reuse the OVH rig)

OVH Managed Kubernetes, BHS5, `load-v1` pool of 4 x b3-64 (16 vCPU / 64 GB). Node label
`bench-role`: 3 `kc` nodes (one KC pod each via REQUIRED podAntiAffinity), 1 `infra` node
(Postgres + Redis + loadgen + Prometheus/Grafana + exporters). Both stacks are `StatefulSet`
replicas:3, `start --optimized`, pinned 6 vCPU / 6Gi, `-Xms2g -Xmx4g -XX:+UseG1GC`,
`KC_DB_POOL_MAX_SIZE=50`, identical realm/load. Only the cache backend differs.

Apples-to-apples guardrails: same image base (above), same flags, `KC_METRICS_ENABLED=true`
and `KC_HTTP_METRICS_HISTOGRAMS_ENABLED=true` on **both** (equal scrape/registry overhead),
same load profile, same warm-up. Stacks run sequentially with the idle stack scaled to 0
(inherited; call it out as a caveat, not a simultaneous A/B).

## Instrumentation (corrected metric names)

| Source | Enable | Signals actually available |
|---|---|---|
| KC native HTTP (Micrometer) | `KC_METRICS_ENABLED=true` + `KC_HTTP_METRICS_HISTOGRAMS_ENABLED=true` | `http_server_requests_seconds_count/_sum` always; `_bucket` (needed for `histogram_quantile` p99) only with the histogram flag |
| Locke `RedisMetrics` (`/metrics`, same endpoint) | bound when `KC_METRICS_ENABLED` binds the Prometheus registry | `keycloak_redis_l2_ops_total{cache,op}`, `keycloak_redis_lua_duration_seconds`, pipeline-batch metric, Caffeine L1 (`cache_gets_total` etc. under the `keycloak_redis_l1` prefix) |
| `redis_exporter` (oliver006), one per Redis node | deploy on infra :9121 | `redis_up`, `redis_instance_info{role}`, `redis_connected_slaves`, `redis_master_link_up`, `redis_sentinel_master_status`, repl offsets |
| `node_exporter` :9100 | DaemonSet on kc + infra | `node_cpu_seconds_total`, `node_memory_*`, `node_network_*`, `node_load1` (rule out "the box was the bottleneck") |
| `kube-state-metrics` :8080 | deploy + cluster-read RBAC | `kube_pod_container_status_restarts_total`, `kube_pod_status_ready`, `kube_statefulset_status_replicas_ready/updated` |

**Latency is reported from Gatling `KCBRESULT`, not Prometheus**, because client-side p99 is
the user-visible number and avoids the histogram-bucket caveat. Prometheus p99 is shown
alongside as corroboration only.

### Metrics that do NOT exist (cut from the plan)

- `keycloak_admin_events_total` — no such meter. Do not use.
- User-event metrics (`keycloak_user_events_total{event=...}`, lowercase `event` tag) require `event-metrics-user-enabled=true` + the `user-event-metrics` feature, not just `KC_METRICS_ENABLED`. Only enable if S5 needs them; otherwise use endpoint error-rate + Gatling KO counts.
- `keycloak_redis_l1_invalidations_published/received_total` exist but are **dead code** (no caller in `L1InvalidationBus`). The cross-node consistency graph needs the optional pre-work below or it is a log line, not a metric.
- `keycloak_redis_l2_duration_seconds` records the timer **only for `hgetall`** today; other ops bump the counter but skip the timer.

## Scenarios

Fixed shape per scenario: setup / chaos / metric / expected / honest caveat / confidence.

| # | Scenario | Chaos | Primary metric | Expected (prior run, 26.6.1-era) | Confidence |
|---|---|---|---|---|---|
| S6 | Steady-state parity (control, earns trust) | none; 80/160/250 ups x60s | `KCBRESULT` rps/p99; `http_server_requests` rate | throughput parity ~0.1%, 0 errors; p99 Locke 104/91/151 vs Infinispan 64/64/167ms | High |
| S1 | Pod loss (KC pod dies, Redis healthy) — **the lede** | `kubectl delete pod --force` 1 then 2 pods at T+45s under 80 ups for 150s (`resilience.sh`) | `KCBRESULT` p99/failed%; per-pod p99 timeline; request-rate shift to survivors; `kube_pod_*_restarts` | Infinispan ~31-40s JGroups stall (p99 31,033 / 39,688ms); Locke sub-second (908 / 2,609ms) | High (directional) |
| S4 | Cross-version rolling upgrade under load | `kubectl set image` mid-stream, windowed login load (`op-upgrade2.sh`) | per-window `KCBRESULT`; `kube_statefulset_status_replicas_updated/ready`; error timeline | Locke rolls clean: 26.3.5→26.6.1 ~85s bump p99 2,185ms; 26.6.1→26.6.2 peak 1,428ms, 0 failures | Medium |
| S2 | Redis-HA failover, Sentinel **and** Cluster (must be built on K8s) | kill the Sentinel master / a Cluster primary under load (B only) | `redis_instance_info{role}` flip, `redis_connected_slaves`, `redis_sentinel_master_status`; windowed `KCBRESULT` across the kill | rides through with a blip bounded by the Redis failover timer, recovers automatically; Cluster newly functional via the cluster-write fix | Lower (new K8s manifests; only compose smokes exist today) |
| S3 | Total Redis outage + the tunable `KC_CACHE_REDIS_TIMEOUT` | standalone Redis, kill it under ~200 ups; run default 2000ms vs a tuned 500ms pass | `min(redis_up)` outage band; KC error rate + p99; recovery | fail-fast at the command timeout (no thread hang), full recovery; **Locke-vs-Locke** (26.6.1 hang vs fixed) not Locke-vs-Infinispan | Medium |
| S5 | Session durability (woven through S1/S3, not a separate run) | during S1/S3: establish a session, kill, then refresh + authenticated call | survival table; error rate on `*token*`/`*userinfo*` endpoints through the kill | persisted (JPA) sessions survive a pod loss and a Redis outage | Medium (asserted from architecture; measure it) |

## Build-out work (what does not exist yet)

1. **Commit this session's fixes and rebuild the Locke 26.6.3 image** (cluster-write fix, `cache-redis-timeout` fix, HA-topology work). Rebuild both bench images on KC 26.6.3; pin by digest.
2. **K8s Redis-HA manifests** mirroring compose `D` (Sentinel: 1 master + 1 replica + 3 sentinels) and `E` (Cluster: 3 primaries + 3 replicas), on the infra pool. Today these exist only as single-host docker-compose.
3. **Chaos scripts** for the new scenarios: a Redis-primary-failover script (port `resilience.sh`) and a session-survival probe (S5). Commit them; do not run ad hoc.
4. **Observability stack manifests**: Prometheus (static scrape of the 3 KC podIPs:9000 + exporters), Grafana, `redis_exporter` (per Redis node), `node_exporter`, `kube-state-metrics` + its cluster-read RBAC. Pin all image digests; pin scrape interval to the `rate()` window.
5. **ConfigMap creation helper** for `bench-scripts` / `dataset-provider` (the `kubectl create configmap` commands are not in the repo today, so `90-runner.yaml` cannot launch reproducibly without them).
6. **Optional code pre-work** (only if we want these specific graphs): wire `RedisMetrics` into `L1InvalidationBus.publish()/handleIncoming()` so the L1-invalidation counters are non-zero (cross-node consistency graph); record `l2Timer` for all ops, not just `hgetall`.

## Deliverables

- Grafana dashboards: "Locke vs Infinispan resilience" (p99 timeline + error rate + request-rate-to-survivors + pod up/down), "Locke Redis internals" (L1 hit ratio, L2 ops, Lua/pipeline timings), "Redis HA failover" (role flip, replication, failover window).
- `queries.md`: every blog claim → exact PromQL + the corroborating `KCBRESULT` line.
- Prometheus range-query CSV dumps per scenario into `results/<date>/prometheus/`, beside the Gatling logs, so graphs are reproducible offline.
- Rendered blog graphs (primary: S1 pod-loss p99 timeline; secondary: S3 outage before/after fail-fast).
- New dated `REPORT.md` + `results/<date>/` tree mirroring `2026-05-24`, with a zipped evidence archive.

## Blog outline

1. Hook: the S1 pod-loss p99 graph (sub-second vs ~31s) on the first screen.
2. Setup in one paragraph: Locke = Keycloak with the cache on Redis, sessions still in JPA.
3. Vocabulary: the three failure modes ([CONTEXT.md](../../docs/CONTEXT.md)) so readers stop conflating them.
4. How we measured: the apples-to-apples rig + client-vs-server instrumentation.
5. Six scenarios in fixed shape (setup / chaos / graph / story / caveat): S6 → S1 → S4 → S2 → S3 → S5.
6. The honest ledger, in the body not the footnotes: added Redis dependency, the small latency trade, single-run/dev-grade caveat, recovery-vs-failover-speed split.
7. How to reproduce: the compose rigs + new K8s HA/observability manifests + scripts.
8. Close: the trade in one sentence + the Skycloak managed option.

## Honest ledger (bake into the post, not the footnotes)

- **Recovery vs failover speed.** Recovery from a Redis-HA event (reconnect, re-subscribe, L1 flush) is a property of Locke. The *speed* of the failover is your Redis config (Sentinel `down-after-milliseconds` / `failover-timeout`, Cluster `node-timeout`). Locke cannot make a 30s Sentinel timer faster.
- **Added dependency.** Redis is an operational dependency embedded Infinispan does not have. A total Redis outage (S3) is a failure mode that literally cannot occur with in-process Infinispan.
- **Single-run, dev-grade.** The headline numbers are single, sequential runs on b3-64 nodes in one region; A and B did not run simultaneously. Present as directionally strong, not as SLOs.
- **S1 is pod-deletion, not node drain.** The StatefulSet reschedules on the same node, so S1 exercises Infinispan's JGroups rebalance vs Redis's no-op. If the lede says "when a node dies," either add a real `kubectl drain`/cordon variant or say "pod loss."
- **S3 is Locke-vs-Locke** (old hang vs fixed fail-fast), not Locke-beats-Infinispan. Frame it separately.
- **S4 framing.** A mixed-version rolling Infinispan cluster across an incompatible major is documented as unsupported upstream; the recommended path is a brief planned restart (<10s startup). Locke removes one constraint; it is not a blanket no-downtime-upgrade promise.
- **S6 latency is a small real trade**, not a win. The 250-ups crossover (Locke 151 vs 167ms) is within run-to-run noise on dev nodes; do not claim Locke is faster.
- **ElastiCache is inferred**, validated against a local rig and a one-off smoke, not run continuously against managed Redis.
