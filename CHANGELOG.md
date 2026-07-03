# Changelog

All notable changes to Locke are documented here.

Locke uses **composite versioning**: the version is the upstream Keycloak version it was
built from, plus a build number. `26.6.2-3` means "Keycloak 26.6.2, Locke build 3." This is
the Percona Server / Amazon Corretto convention.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [26.6.4-1] - 2026-07-03

### Changed
- Built from Keycloak 26.6.4. See the upstream release notes.

## [26.6.4-4] - 2026-07-03

### Fixed
- Redis session behavioral parity, wave 2 (completes #44): idempotent session/client-session
  creation (no more concurrent PK-violation 500s), load-time expiration (idle + max lifespan,
  online/offline), a per-realm-leased periodic expiration sweep, user-removal session cascade,
  single-use get() empty-notes fix, and L1-only caches answering entrySet() so predicate
  invalidations (e.g. deleted roles in client scopes) actually run. Conformance gate green
  (126 tests, 0 failures); no upstream files touched.

## [26.6.4-3] - 2026-07-03

### Fixed
- Redis session behavioral parity, wave 1: client_credentials (transient) sessions work
  and are never persisted (#43); client sessions created in-transaction are visible on the
  session adapter; client sessions are expiration-checked on load; auth-session TTL now
  follows SessionExpiration (was capped at 60s); auth-session tab removal and limit
  eviction actually delete. Tracked in #44; found by the jpa+redis conformance gate.

## [26.6.4-2] - 2026-07-01

### Fixed
- External identity-provider brokering (OIDC/SAML) and X.509 CRL revocation now work under
  `KC_CACHE=redis`. The Infinispan `PublicKeyStorageProvider` and `CrlStorageProvider` were
  disabled under redis with no replacement, so `getProvider(...)` returned null and external
  token/signature verification failed. Adds Redis-mode providers on a per-node cache.
  Reported in #22 / #40; see `docs/adr/0004`.

## [26.6.4-1] - 2026-06-29

Rebased onto upstream Keycloak 26.6.4 (a patch release). Also lands the Redis cluster
hardening that was pending since 26.6.3-3.

### Added
- Redis cluster: Redisson `readMode`/`subscriptionMode` pinned to `MASTER`, so Locke connects
  to Azure Managed Redis with the OSS clustering policy (which rejects `READONLY`) and any
  strict OSS cluster. Adds a configurable cluster topology-refresh cadence (default 30s) and
  explicit connection-pool sizing.

### Changed
- Built from Keycloak 26.6.4 (was 26.6.3).

## [26.6.3-3] - 2026-06-24

### Fixed
- Single-use object removal no longer requires Redis 6.2. The atomic get-and-delete now runs as a
  Lua `GET`+`DEL` script (`EVAL`, Redis 2.6+) instead of native `GETDEL`, so Locke runs on **Redis
  6.0**, notably classic Azure Cache for Redis. Reported in #22 / #40; see `docs/adr/0003`.

## [26.6.3-2] - 2026-06-16

Resilience hardening across the Sentinel and Cluster deployment modes. The focus is staying
available through a primary loss: recover fast, fail fast when the cache is gone, and never
serve stale cache afterward. No upstream Keycloak change (still built from 26.6.3).

### Changed
- Cluster mode now keeps the in-JVM L1 cache (Caffeine) active, with cross-node
  invalidation over Redis pub/sub, the same as Standalone and Sentinel. It previously ran
  with L1 disabled, paying a Redis round trip on every local-cache read.
- Cluster clients now refresh their slot-to-node topology periodically and on connection
  events, so a shard failover or reshard no longer strands the client on a node that has
  gone away.
- The default Redis command timeout is now 1000ms (was effectively 2000ms). The timeout is
  applied per command and a single request can issue several, so lowering it tightens the
  worst-case tail latency during a Redis outage. Tune with `KC_CACHE_REDIS_TIMEOUT`.

### Fixed
- Cluster mode can now serve traffic. Every distributed-cache write (auth sessions, login
  failures, single-use tokens) previously failed in `redis-cluster://` mode because the
  cache adapters cast the Lettuce connection to the standalone type; on a real cluster that
  threw `ClassCastException` and returned HTTP 500 on the first login or token request.
  Commands now flow through the connection's common command supertype, so the same code path
  works for standalone, sentinel, and cluster. Standalone and sentinel behavior is unchanged.
- `KC_CACHE_REDIS_TIMEOUT` is now honored. The option was exposed and documented but never
  read by the connection factory, so the timeout was hardcoded regardless of the setting. It
  now flows through to both the Lettuce command timeout and the Redisson client.
- L1 caches are flushed when the pub/sub invalidation channel reconnects (for example
  after a failover). A node that missed invalidation messages while disconnected no longer
  serves stale entries once it comes back.
- Sentinel connections no longer fail at connect time with "Host must not be empty." The
  client builds the Sentinel URL from the configured sentinel hosts and master id
  directly, rather than round-tripping through a parsed host.

### Added
- Cache metrics now cover write paths: `keycloak_redis_l2_duration_seconds` is recorded for
  all Redis operations (previously only `hgetall`), and the
  `keycloak_redis_l1_invalidations_published_total` / `_received_total` counters now emit
  real values instead of staying at zero.

### Notes
- Sentinel and Cluster deployment modes are documented in
  [docs/redis-modes.md](./docs/redis-modes.md), including ElastiCache (cluster-mode
  enabled and disabled) and Sentinel-vs-Cluster guidance.
- A runnable failover smoke (`benchmark/compose/failover-smoke.sh`) brings up a real
  Sentinel or Cluster topology, kills the primary, and asserts Locke keeps serving. A
  Redis-HA failover is a brief outage bounded by your Redis failover timers, not a
  zero-downtime event; Locke recovers automatically and fails fast in the meantime.

## [26.6.3-1] - 2026-06-04

### Changed
- Rebased Locke onto Keycloak 26.6.3 (upstream released 2026-06-04). No Locke-side
  functional changes: the Redis cache backend, TLS support (`rediss://`), and runtime
  `KC_CACHE_REDIS_PASSWORD` / `_USERNAME` handling are unchanged from `26.6.2-3`. See the
  upstream [Keycloak 26.6.3 release notes](https://github.com/keycloak/keycloak/releases/tag/26.6.3).

## [26.6.2-3] - 2026-06-03

### Security
- Redis connections can now use TLS via the `rediss://`, `rediss-sentinel://`, and
  `rediss-cluster://` URL schemes. Required for connecting to any of the major
  managed-Redis services (AWS ElastiCache with in-transit encryption, Azure Cache for
  Redis, Upstash, Redis Cloud) without a stunnel sidecar. Closes
  [sky-cloak/locke#22](https://github.com/sky-cloak/locke/issues/22).
- Server certificate chain validation is mandatory when TLS is in use. Hostname
  verification (CN/SAN match) is on by default; opt out via
  `KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME=false`. The fully-insecure "trust any cert" mode is
  not exposed.
- TLS options on a plain `redis://` URL refuse to boot, instead of silently sending
  plaintext. Catches the common "forgot the second `s`" misconfiguration.

### Added
- `KC_CACHE_REDIS_TLS_CA_FILE`: path to a PEM file holding the CA chain that signed the
  Redis server cert. For private CAs and on-prem Redis. Managed services with public CA
  chains do not need this knob.
- `KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME` (default `true`): hostname verification toggle.
- New documentation page: [docs/redis-security.md](./docs/redis-security.md) with a
  managed-Redis matrix and operational guidance.

### Fixed
- `KC_CACHE_REDIS_PASSWORD` and `KC_CACHE_REDIS_USERNAME` are now honored at runtime.
  The Quarkus mappers exposed these options in a prior release, but the factory never
  read them; only URL-embedded userinfo (`redis://user:pass@host`) worked. Connection
  authentication via env var now functions as documented. When both URL userinfo and the
  env var are set with different values, the env var wins and a WARN line is logged so
  the override is auditable.

### Notes
- Phase 2 hardening (mTLS, AWS IAM auth tokens, AWS Secrets Manager credential
  rotation, TLS protocol/cipher controls, cert reload without restart) is a planned
  follow-up release.
- This release ships on the `26.6.2-3` line only. The 26.6.1 and 26.3.5 maintenance
  lines stay on plaintext Redis for now; happy to cherry-pick on request. Open an issue
  with the line you need.

## [26.6.2-2] - earlier
## [26.6.1-2] - earlier
## [26.3.5-3] - earlier

See the [GitHub Releases](https://github.com/sky-cloak/locke/releases) page for earlier
notes.
