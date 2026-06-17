# Locke domain glossary

Locke is a Keycloak distribution with a Redis cache backend. This glossary fixes the
vocabulary for Locke's distinct concepts so docs, tickets, and benchmarks do not
conflate them. Glossary only: no implementation details.

## Failure modes

Three distinct events get loosely called "failover" or "an outage." They are not the
same and their guarantees differ. Keep them distinct in docs and benchmark claims.

### Pod loss
A Keycloak (Locke) pod dies or is evicted while the Redis backend stays healthy.
Surviving pods keep serving from shared Redis. This is the headline resilience result
(sub-second p99 where embedded Infinispan stalls ~31s on JGroups rebalance). A property
of Locke.

### Redis-HA failover
A Redis node in an HA topology fails and the topology promotes a replacement: Sentinel
promotes a replica to master, or a Redis Cluster shard fails over to its replica. Locke
must detect the change and reconnect to the new primary. Recovery is a property of
Locke; failover SPEED is a property of the Redis deployment (Sentinel
down-after-milliseconds, Cluster node-timeout), not of Locke. This is the mode the
HA-topologies hardening work targets.

### Redis outage
The Redis backend is fully unreachable (no HA, or every node down). Locke cannot serve
cache operations and degrades within a bounded timeout rather than hanging; it recovers
automatically when Redis returns. Logins survive a Redis outage because user sessions
persist to JPA, not Redis.

## Redis deployment modes

### Standalone
A single Redis endpoint. No automatic failover.

### Sentinel
Redis replication fronted by Sentinel processes that monitor the master and promote a
replica on failure. Addressed by the Sentinel endpoints plus a master name.

### Cluster
Sharded Redis: keys distributed across shards by hash slot, each shard a primary with
replicas. The client tracks slot-to-node topology and must refresh it when the topology
changes.

### ElastiCache
AWS-managed Redis. "Cluster mode enabled" is a Cluster (sharded, configuration
endpoint). "Cluster mode disabled" is replication behind a primary endpoint that AWS
repoints on failover. Engine-compatible with self-hosted Sentinel / Cluster.
