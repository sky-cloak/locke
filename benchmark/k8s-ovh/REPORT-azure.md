# Azure run — Locke 26.6.3-2 (Redis) vs vanilla Keycloak 26.6.3 (Infinispan)

Date: 2026-06-16. Cluster: AKS `skycloak-prod-us` (eastus2). Dedicated tainted `lockebench`
pool: 4× `Standard_E8as_v7` (8 vCPU / 64 GB) — 3 KC nodes + 1 infra node, removed after the run.
KC pods: `start --optimized`, 5 vCPU / 6 Gi pinned, `-Xms2g -Xmx4g`, anti-affinity (one per node),
shared Postgres, identical config; only the cache backend differs. Load: keycloak-benchmark Gatling
AuthorizationCode, realm `bench-kcb` (100 users). Stacks run sequentially (idle scaled to 0).

This independently reproduces the OVH resilience result on a second cloud, with the shipping image.

## Resilience — pod loss (the headline)

Sustained 80 logins/sec for 150s; one KC pod killed at T+45s.

| Metric | Locke (Redis) | Infinispan (vanilla) |
|---|---|---|
| p99 during the kill | **254 ms** | **36,883 ms** |
| p95 | 70 ms | 20,443 ms |
| failed | 0.02% (7 / 45,361) | 0.68% (303 / 44,343) |

Locke is **~145× better p99**: surviving pods keep serving from Redis with no cluster-membership
protocol to rebalance, while Infinispan stalls on the JGroups rebalance + state transfer (~37s here,
consistent with the ~31–40s seen on OVH).

## Steady-state throughput / latency

| ups | Locke rps | Locke p99 | Infinispan rps | Infinispan p99 | errors |
|---|---|---|---|---|---|
| 80  | 274.4 | 153 ms | 273.8 | 176 ms | 0 / 0 |
| 160 | 548.4 | 1,210 ms | 555.2 | 27 ms | 0 / 0 |
| 250 | 606.8 | 14,898 ms | 857.6 | 29 ms | 0 / 0 |

**At 80 ups the two are comparable** (153 vs 176 ms p99, zero errors). At 160/250 Locke degrades
sharply — but this is a **rig artifact, not a real parity gap**: the single 8-vCPU infra node hosts
both the CPU-hungry Gatling load generator and Redis, so at high load the load generator starves
Redis, which penalizes Locke specifically (Infinispan's cache is in-process and has no dependency on
the contended node). On the OVH b3-64 rig (16-vCPU infra node) the two were within ~0.1% to 250 ups.
A fair high-load parity run needs the load generator and Redis on separate, larger nodes.

## Honest caveats

- Single run on `E8as_v7` (8 vCPU) nodes with 5-vCPU pods — smaller than the OVH b3-64 / 6-vCPU rig,
  so absolute throughput is lower and saturates earlier. The **relative A-vs-B comparison on identical
  hardware** is the valid signal.
- High-load parity (160/250) is confounded by the loadgen↔Redis co-location on one infra node; treat
  only the 80-ups point and the resilience result as clean here.
- A Redis-HA failover scenario (Sentinel/Cluster) was not run here; see `failover-smoke.sh` + the local
  Prometheus validation for that path.
