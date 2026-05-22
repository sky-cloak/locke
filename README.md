# Locke

**A drop-in Keycloak distribution that runs on Redis instead of Infinispan, so you can operate it with a managed cache your cloud already provides.**

[![Build](https://github.com/sky-cloak/locke/actions/workflows/pr.yml/badge.svg)](https://github.com/sky-cloak/locke/actions/workflows/pr.yml)
[![Benchmarks](https://img.shields.io/badge/benchmarks-methodology%20%26%20results-blue)](./benchmark/RESULTS.md)
[![Keycloak compatibility](https://img.shields.io/badge/Keycloak-26.6.1-blue)](./COMPATIBILITY.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-green)](./LICENSE.txt)

Locke is a distribution of [Keycloak](https://www.keycloak.org) that ships with
**both** cache backends (the upstream embedded Infinispan and a Redis backend)
and lets the operator pick one at boot. When you don't pick Redis, Locke is the
Keycloak it was built from, unchanged.

> Keycloak gives you the key. Locke gives you the choice.

Why does this exist? See **[WHY.md](./WHY.md)**.

## Quickstart (5 minutes)

Both backends ship in the same binary. Choose with one environment variable.

```bash
# Default: embedded Infinispan, identical to upstream Keycloak
docker run --rm -p 8080:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  ghcr.io/sky-cloak/locke:26.6.1-1 start-dev

# Redis backend: point it at any Redis / Valkey / wire-compatible store
docker run --rm -p 8080:8080 \
  -e KC_CACHE=redis -e KC_CACHE_REDIS_URL=redis://my-redis:6379 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  ghcr.io/sky-cloak/locke:26.6.1-1 start-dev
```

Or with compose (Postgres + Redis + Locke):

```bash
docker compose -f docker-compose-redis.yml up
```

## Architecture

```
        HTTP request (login / refresh / token)
                     │
                     ▼
        Keycloak SPI dispatch  (Realm / User / Client / AuthSession / …)
                     │
                     ▼
        Cache adapter layer  (model/redis)
          ├─ L1: Caffeine, in-JVM (~100 ns), bounded 10k + 60s TTL
          └─ L2: Redis / Valkey   (shared, cross-pod)
                     │                     cross-pod invalidation
                     ▼                     via Redis pub/sub (no JGroups)
        PostgreSQL (source of truth)
```

`KC_CACHE=infinispan` swaps the adapter layer back to the upstream embedded
Infinispan stack with no other change. Full design notes:
[docs/redis-cache-architecture.md](./docs/redis-cache-architecture.md).

## Compatibility

Locke uses **composite versioning**: the Locke version is the upstream Keycloak
version it was built from, plus a build number. `26.6.1-1` means "Keycloak 26.6.1,
Locke build 1." This is the Percona Server / Amazon Corretto convention.

| Locke | Built from Keycloak | Status |
|---|---|---|
| `26.6.1-1` | 26.6.1 | current |
| `26.3.5-1` | 26.3.5 | previous |

See [COMPATIBILITY.md](./COMPATIBILITY.md) for the full matrix and support window.

## Configuration

| Option | Default | Purpose |
|---|---|---|
| `KC_CACHE` | `infinispan` | Cache backend: `infinispan` or `redis` |
| `KC_CACHE_REDIS_URL` | (none) | Redis connection URL (required when `KC_CACHE=redis`) |

Every other Keycloak option works exactly as upstream. Locke adds no new database,
no new admin API, and no new operational concept beyond "you may point the cache at
Redis."

## Performance

In 3-pod cluster tests behind a load balancer, the Redis backend keeps pace with
embedded Infinispan at sustained load. We are validating the exact parity figure
on isolated cloud infrastructure before publishing a number. Methodology,
preliminary numbers, and caveats are in
[benchmark/RESULTS.md](./benchmark/RESULTS.md), and CI refreshes them on each
release.

## Don't want to operate this yourself?

[Skycloak](https://skycloak.io) runs managed Keycloak (and Locke) for you: the
"I want the choice without the on-call" option.

## License

Apache License 2.0, inherited from upstream Keycloak. See [LICENSE.txt](./LICENSE.txt)
and [NOTICE](./NOTICE). "Keycloak" is a trademark of Red Hat / CNCF; see
[TRADEMARK.md](./TRADEMARK.md). Contributions: [CONTRIBUTING.md](./CONTRIBUTING.md).
Security: [SECURITY.md](./SECURITY.md).
