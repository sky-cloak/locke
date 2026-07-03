# Choosing & deploying a Redis backend for Locke

Locke serves Keycloak's realm/user/authorization/session caches from Redis instead of embedded
Infinispan. This guide is the **operational decision**: where to run that Redis, how to wire it up,
and what each choice costs you — especially on failover. The numbers behind every claim here are in
the benchmark: [../benchmark/k8s/REPORT.md](../benchmark/k8s/REPORT.md). For how the cache layer
works internally, see [redis-cache-architecture.md](./redis-cache-architecture.md); for URL schemes
and HA modes, [redis-modes.md](./redis-modes.md); for TLS/auth, [redis-security.md](./redis-security.md).

## TL;DR

| Option | Use when | Latency | Failover |
|---|---|---|---|
| **Co-located Redis** (same VMs/VPC, ideally a small cluster) | You want the best of everything | Best (sub-100ms p99) | Best |
| **Managed OSS cluster-mode** (AWS ElastiCache, or any service you talk to *directly, shard-by-shard*) | You already run managed Redis | +network hop, fine | Good |
| **Single-proxy managed Redis** (e.g. Azure Managed Redis) | — avoid for this workload | Poor under load (proxy funnel) | — |
| **Stay on Infinispan** | You don't need HA | Lowest (in-process) | **Disqualifying — 31–40s stall on node loss** |

**The one rule:** keep Redis on the same network (VPC/AZ) as Keycloak, and prefer a backend Locke can
talk to **directly per shard** rather than through a single managed proxy endpoint.

## Why the architecture matters

Locke's client (Lettuce) opens one connection per Redis shard and talks straight to it. That's how
**co-located Redis and AWS ElastiCache cluster-mode** work — and both perform well (ElastiCache: p99
181ms at 80 logins/sec, 522ms through a pod kill, zero errors).

A **single-proxy-endpoint** managed Redis (Azure Managed Redis in its working mode) funnels *all*
traffic through one managed proxy. Per operation it's fine (~0.6ms), but a login does several Redis
round-trips and at ~850 logins/sec the proxy becomes the bottleneck: p99 **4,940ms** at 80 ups
(~27× worse than ElastiCache). Locke-side mitigations (an extra L1 cache, a different connection
model) were prototyped and **made it worse** — the proxy ceiling is the problem, not the client. So:
avoid proxy-architecture managed Redis for Locke's write-heavy auth workload.

## Configuration

Switch the cache backend and point at Redis:

```bash
KC_CACHE=redis
KC_CACHE_REDIS_URL=redis://your-redis:6379
```

URL schemes (see [redis-modes.md](./redis-modes.md)):

| Topology | Scheme | Example |
|---|---|---|
| Standalone | `redis://` / `rediss://` | `redis://redis:6379` |
| Sentinel (HA) | `redis-sentinel://` / `rediss-sentinel://` | `redis-sentinel://s1:26379,s2:26379/?master=mymaster` |
| Cluster | `redis-cluster://` / `rediss-cluster://` | `redis-cluster://cfg-endpoint:6379` |

- **TLS** (`rediss…` schemes): most managed Redis enforces it. Custom CA via
  `KC_CACHE_REDIS_TLS_CA_FILE`; hostname verification on by default. See
  [redis-security.md](./redis-security.md).
- **Auth:** `KC_CACHE_REDIS_USERNAME` / `KC_CACHE_REDIS_PASSWORD` (env wins over URL userinfo, and
  doesn't leak into `ps`/logs).
- **Tuning** (rarely needed): `KC_CACHE_REDIS_MAX_POOL_SIZE` (default 64), `…_MIN_IDLE` (16),
  `…_TOPOLOGY_REFRESH_SECONDS` (30, cluster only). A higher-latency managed Redis wants a larger
  pool — required concurrency ≈ ops/sec × per-op latency.
- **Minimum Redis version: 6.0** (so classic Azure Cache for Redis is supported). The cache layer
  only needs `EVAL` (Redis 2.6+) — single-use get-and-delete runs as an atomic Lua `GET`+`DEL`
  rather than native `GETDEL` (which needs 6.2+). Older servers may work but aren't tested. See
  [adr/0003](./adr/0003-redis-command-floor.md).

## Sizing & placement

- **Same network.** Keep Redis in the same VPC/AZ as Keycloak — a cross-region hop is added latency on
  every cache miss.
- **CPU headroom per pod.** A Redis round-trip costs more per-login CPU than an in-process cache hit,
  so Keycloak pods need adequate CPU or they knee early under load (the benchmark saw 160-ups p99 go
  from 1,210ms on undersized pods to 80ms on properly-sized ones — same code).
- **Size the Redis tier for peak.** A small managed tier (few shards) saturates under high login
  rates. Scale shards/nodes to your peak logins/sec; a co-located 3+3 cluster held the full load at
  sub-100ms p99.
- **Don't co-locate Redis with the database under load.** Postgres (session persistence) and Redis
  contending for one node's CPU/IO will cap throughput for *every* backend — give the database its own
  capacity.

## High availability

- For zero-downtime through a **Keycloak pod** loss, Redis already gives you that — cache state lives
  in Redis, so a lost KC pod is stateless (sub-second p99, vs Infinispan's 31–40s JGroups stall).
- For zero-downtime through a **Redis node** loss, run **Redis HA** (Sentinel or Cluster, or a managed
  service with replicas). On a Redis outage Locke's cache path fails fast at a 2s command timeout and
  auto-reconnects when Redis returns — it degrades and recovers rather than hanging, but the cache
  path does error during the outage, so HA Redis is what makes it transparent.

## Cloud notes

- **AWS — ElastiCache (recommended managed path).** Use **cluster-mode-enabled** (OSS), connect with
  `redis-cluster://<configuration-endpoint>:6379` (or `rediss-cluster://` for in-transit encryption),
  in the same VPC, security group allowing 6379 from the Keycloak nodes. Validated viable.
- **Azure — avoid Azure Managed Redis for this workload** (single-proxy funnel; see above). Prefer
  co-located Redis/Valkey, or a direct-to-shard option. If you must use AMR-OSS, Locke pins Redisson
  `readMode=MASTER` so it connects, but the throughput ceiling remains. **Classic Azure Cache for
  Redis (Premium, clustered)** is OSS direct-to-shard with no proxy and is now supported (Locke runs
  on its Redis 6.0) — a reasonable Azure option, though that service retires 2028-09-30.
- **Generic / self-managed.** Co-locate Redis (or Valkey — drop-in, Apache-2.0 license) on the
  Keycloak network; a small Sentinel or Cluster deployment gives you HA and the best numbers.
