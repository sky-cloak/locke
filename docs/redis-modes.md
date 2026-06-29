# Redis deployment modes (Standalone, Sentinel, Cluster, ElastiCache)

Locke connects to whatever Redis topology you run. The mode is chosen by the URL scheme;
nothing else changes. Failure-mode vocabulary used below is defined in [CONTEXT.md](./CONTEXT.md).

| `KC_CACHE_REDIS_URL` scheme | Mode | TLS variant |
| --------------------------- | ---- | ----------- |
| `redis://`                  | Standalone | `rediss://` |
| `redis-sentinel://`         | Sentinel   | `rediss-sentinel://` |
| `redis-cluster://`          | Cluster    | `rediss-cluster://` |

Auth and TLS work the same in every mode: `KC_CACHE_REDIS_PASSWORD` / `KC_CACHE_REDIS_USERNAME`,
and the `rediss` scheme + `KC_CACHE_REDIS_TLS_CA_FILE` (see [redis-security.md](./redis-security.md)).
The L1 (Caffeine) cache and its cross-node invalidation run in all three modes.

## Standalone

```
KC_CACHE_REDIS_URL=redis://redis.example.com:6379
```

A single endpoint, no automatic failover. If Redis is unreachable, Locke degrades within the
client timeout and recovers when Redis returns; logins keep working (sessions are in JPA).

## Sentinel

```
KC_CACHE_REDIS_URL=redis-sentinel://s1:26379,s2:26379,s3:26379?sentinelMasterId=mymaster
```

List the Sentinel endpoints and the monitored master name. Locke discovers the current master
through the Sentinels. When the master fails, the Sentinels promote a replica and Locke
reconnects to it. Recovery is automatic; the recovery *time* is your Sentinel config
(`down-after-milliseconds`, `failover-timeout`), not Locke.

## Cluster

```
KC_CACHE_REDIS_URL=redis-cluster://n1:6379,n2:6379,n3:6379
```

List a few seed nodes. Locke discovers the full topology and refreshes it every 30s plus on
adaptive triggers (MOVED/ASK/reconnect), so a shard failover or a reshard re-routes
automatically instead of stranding the client on a dead node.

## AWS ElastiCache

ElastiCache is managed Redis and uses the same schemes:

- **Cluster mode enabled** (sharded): point at the configuration endpoint with
  `rediss-cluster://<config-endpoint>:6379`. Topology refresh discovers the shards.
- **Cluster mode disabled** (primary + replicas): point at the primary endpoint with
  `rediss://<primary-endpoint>:6379`. On failover ElastiCache repoints that endpoint and Locke
  auto-reconnects.

Use `KC_CACHE_REDIS_PASSWORD` for the auth token. (IAM auth is a planned follow-up.) ElastiCache
is engine-compatible with self-hosted Cluster/Sentinel, so the local Cluster test rig below
exercises the same behavior; validate a real instance with a one-off smoke.

## Choosing Sentinel vs Cluster

Both give automatic failover and both keep the L1 cache. The difference is sharding:

- **Sentinel** — one dataset, replicated, with failover. Simpler to run. The right default for
  HA when one node holds the working set (Locke's caches are small).
- **Cluster** — the dataset is sharded across nodes. Choose it when you need horizontal capacity
  beyond a single node, or you are already on ElastiCache cluster mode.

## Behavior across a Redis-HA failover

When a master/shard fails over, Locke reconnects (Sentinel discovery or Cluster topology
refresh). On reconnect, the L1 invalidation bus re-subscribes and **flushes the local L1**: a
node that was briefly disconnected may have missed invalidations, so its L1 is dropped rather
than risk serving stale realm/client/role/user data. The cost is a brief cold-cache refetch;
the gain is no stale reads after failover.

## Failover test rigs

`benchmark/compose/D-redis-sentinel-3node.yml` and `E-redis-cluster-6node.yml` bring up a real
Sentinel and Cluster topology with Locke attached. To exercise failover: bring the stack up,
then `docker kill` the master (D) or a primary (E) and watch Locke recover.
