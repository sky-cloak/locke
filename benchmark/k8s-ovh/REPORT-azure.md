# Azure run — Locke 26.6.3-2 (Redis) vs vanilla Keycloak 26.6.3 (Infinispan)

Cluster: AKS `skycloak-prod-us` (eastus2). Dedicated tainted bench pools, removed after each run.
KC pods `start --optimized`, anti-affinity (one per node), shared Postgres, identical config; only
the cache backend differs. Load: keycloak-benchmark Gatling AuthorizationCode, realm `bench-kcb`
(100 users); stacks sequential (idle scaled to 0). The load generator and Redis/Postgres each run on
their own dedicated node so neither contends with the KC pods or each other.

Three rigs were run to isolate variables: (1) small/shared, (2) neutral (isolated loadgen, E8/5-vCPU
pods), (3) big (E16 nodes, 8-vCPU pods). The big rig is the authoritative parity result.

## Resilience — pod loss (the headline)

Sustained 80 logins/sec for 150s; one KC pod killed at T+45s. Stable across every run:

| Run | Locke p99 / failed | Infinispan p99 / failed |
|---|---|---|
| Big (E16) | **110 ms** / 0.01% | **37,511 ms** / 0.68% |
| Neutral (E8) | 108 ms / 0.02% | 34,556 ms / 0.46% |
| First (E8) | 254 ms / 0.02% | 36,883 ms / 0.68% |

~340× better p99. Locke serves straight through (surviving pods read from Redis, no cluster-membership
protocol); Infinispan stalls ~35s on JGroups rebalance + state transfer. Matches OVH (~31–40s).

## Throughput parity — big rig (3× E16as_v7 KC, 8-vCPU pods)

| ups | Locke rps | Locke p99 | Infinispan rps | Infinispan p99 | errors |
|---|---|---|---|---|---|
| 80  | 273.2 | 78 ms  | 274.3 | 29 ms  | 0 / 0 |
| 160 | 546.0 | 80 ms  | 547.5 | 133 ms | 0 / 0 |
| 250 | **855.7** | 145 ms | **856.0** | 27 ms | 0 / 0 |

**~100% throughput parity** — at 250 ups Locke 855.7 vs Infinispan 856.0 req/s (within 0.04%), both
reaching the target with 0 errors. This reproduces the OVH parity result on a second cloud with the
shipping image. Locke carries modestly higher latency (the Redis round-trip per login — the honest
trade), but sub-150ms and error-free across the range.

## What the rig progression showed (parity is node-size-bound, not a Locke limit)

| ups | Small/shared E8 | Neutral E8 (isolated loadgen) | Big E16 (8-vCPU pods) |
|---|---|---|---|
| 160 Locke p99 | 1,210 ms | 390 ms | 80 ms |
| 250 Locke rps / p99 | 606 / 14.9s | 640 / 12.2s | 855.7 / 145ms |

- The 160-ups blowup on the shared rig was the load generator starving Redis (both on one node);
  isolating the loadgen fixed it.
- The 250-ups saturation was a CPU-headroom limit of the small E8/5-vCPU pods — *both* stacks kneed
  there. On E16/8-vCPU pods both reach the full ~856 req/s target at full parity. Not a Locke
  regression — Locke just needs adequate per-login CPU headroom (a Redis round trip costs more than an
  in-process cache hit), which OVH's b3-64 rig and this E16 rig both provide.

## Caveats

- Single 60s runs per point; treat as directional, not SLOs. Resilience and the big-rig parity are the
  clean signals.
- Redis-HA failover (Sentinel/Cluster) not run here; see `failover-smoke.sh` + the local Prometheus
  validation for that path.
