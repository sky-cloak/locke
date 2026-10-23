# 3. Redis command floor: stay runnable on Redis 6.0

Status: Accepted (2026-06-24)

## Context

A large base of managed Redis users runs the classic **Azure Cache for Redis** (community
Redis, which tops out at version **6.0**). Microsoft is retiring that service on
**2028-09-30**, so it remains a real deployment target for roughly two more years.

Locke's single-use-object removal (`RedisSingleUseObjectProvider.remove`, backed by
`LettuceCacheAdapter.remove`) used `GETDEL` — an atomic get-and-delete that was added in
**Redis 6.2**. On Redis 6.0 it fails with `ERR unknown command 'GETDEL'`, so Locke cannot
run against classic Azure Cache for Redis at all. This was reported by an external user
(GitHub #22, tracked as #40). An audit of the cache layer found `GETDEL` is the *only*
command Locke uses that requires a server newer than 6.0.

## Decision

**Keep Locke runnable on Redis 6.0.** No command that requires a server newer than 6.0 is
used without a fallback.

The single-use get-and-delete is implemented as an atomic **Lua `GET`+`DEL` script**
(`EVAL`, available since Redis 2.6) run through the existing `LuaScripts` harness
(`EVALSHA` with a `NOSCRIPT`→`EVAL` retry), not native `GETDEL`. The script is loaded with
the others at startup and invoked via a byte[]-returning path (`ScriptOutputType.VALUE`),
so the serialized value round-trips intact on the `ByteArrayCodec` connection.

The Lua path is used **unconditionally** (no Redis-version detection): `EVALSHA` is a single
round-trip, identical to `GETDEL`, so there is no measurable cost on modern Redis and no
branch to maintain.

Advertised support floor: **Redis 6.0+ (including classic Azure Cache for Redis)**. The
cache layer itself only needs `EVAL` (2.6+), so older servers may work but are untested.
Test floor is `redis:6.0`.

## Alternatives considered

- **Native `GETDEL` (status quo).** Rejected: Redis 6.2+, which excludes the entire classic
  Azure Cache for Redis install base and any pre-6.2 server.
- **Detect the server version and prefer native `GETDEL` on 6.2+, Lua on older.** Rejected:
  adds a startup capability probe plus a permanent hot-path branch (single-use remove runs on
  every login) for an unmeasurable latency gain, since `EVALSHA` ≈ `GETDEL`.
- **Plain `DEL` (no get).** Rejected: `SingleUseObjectProvider.remove` is contractually a
  get-and-delete — its return value is the consumed object (action tokens, auth codes), and
  callers use it. A bare delete would change behaviour.

## Consequences

- Locke runs on Redis 6.0, unblocking classic Azure Cache for Redis (and older on-prem
  Redis) for the remainder of that service's life.
- Single-use remove now records `keycloak_redis_lua_invocations_total{script=get_del}` in
  addition to the existing `keycloak_redis_l2_ops_total{op=getdel}` (kept for dashboard
  continuity).
- Future contributors must not introduce a command newer than 6.0 without a Lua/EVAL
  fallback. This is guarded by a `redis:6.0` integration test (on-demand cross-version
  proof) and a CI unit test asserting `remove()` goes through the Lua path rather than a
  native `getdel`.
- The floor can be raised deliberately later — most naturally once classic Azure Cache for
  Redis retires (2028-09-30), after which native `GETDEL`/`GETEX` become safe to adopt.
