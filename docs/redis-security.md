# Redis security (TLS + authentication)

Locke's Redis backend supports TLS (`rediss://`) and Redis ACL / legacy AUTH out of the
box. This document covers what's available in Locke `26.7.0-1` and what's coming next.

> Cross-references:
> - Architecture: [redis-cache-architecture.md](./redis-cache-architecture.md)
> - Deployment modes (Sentinel / Cluster / ElastiCache): [redis-modes.md](./redis-modes.md)
> - General quickstart: [README.md](../README.md#quickstart-5-minutes)
> - Tracking issue: [sky-cloak/locke#22](https://github.com/sky-cloak/locke/issues/22)

## TL;DR

```bash
# Public-CA managed Redis (ElastiCache, Azure Cache, Upstash, Redis Cloud): just `rediss://`.
docker run --rm -p 8080:8080 \
  -e KC_CACHE=redis \
  -e KC_CACHE_REDIS_URL=rediss://my-redis.example.com:6380 \
  -e KC_CACHE_REDIS_PASSWORD=$REDIS_PASSWORD \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  ghcr.io/sky-cloak/locke:26.7.0-1 start-dev

# Private-CA Redis (self-hosted, internal cluster): point at the CA bundle.
docker run --rm -p 8080:8080 \
  -e KC_CACHE=redis \
  -e KC_CACHE_REDIS_URL=rediss://redis.internal:6379 \
  -e KC_CACHE_REDIS_PASSWORD=$REDIS_PASSWORD \
  -e KC_CACHE_REDIS_TLS_CA_FILE=/etc/ssl/redis/ca.crt \
  -v /etc/ssl/redis:/etc/ssl/redis:ro \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  ghcr.io/sky-cloak/locke:26.7.0-1 start-dev
```

## URL schemes

| Scheme              | Mode       | Transport      |
| ------------------- | ---------- | -------------- |
| `redis://`          | Standalone | Plaintext      |
| `rediss://`         | Standalone | TLS            |
| `redis-sentinel://` | Sentinel   | Plaintext      |
| `rediss-sentinel://`| Sentinel   | TLS            |
| `redis-cluster://`  | Cluster    | Plaintext      |
| `rediss-cluster://` | Cluster    | TLS            |

The trailing `s` toggles TLS for all three deployment modes.

## Authentication

Locke supports both Redis ACL (Redis 6+, `user:password`) and legacy AUTH (password only).
You can pass credentials either via URL userinfo or env vars:

```bash
# Via URL userinfo (concise)
KC_CACHE_REDIS_URL=rediss://alice:secret@redis.example.com:6380

# Via env vars (recommended for production)
KC_CACHE_REDIS_URL=rediss://redis.example.com:6380
KC_CACHE_REDIS_USERNAME=alice
KC_CACHE_REDIS_PASSWORD=secret
```

**Precedence**: when both are present, **env vars win** over URL userinfo. A single WARN
line is logged at boot so the override is auditable. URLs leak in `ps` output, heap
dumps, error stacks, and audit logs; env vars / secret mounts are the conventional
secrets surface, so Locke prefers them when both are set.

For legacy AUTH-only Redis (no username), use `redis://:password@host` or just set
`KC_CACHE_REDIS_PASSWORD` with no `KC_CACHE_REDIS_USERNAME`.

## TLS trust

By default Locke trusts the certificates in the JVM truststore (`cacerts`). That covers
every cloud-managed Redis offering whose certificate chains roll up to public CAs
(ElastiCache, Azure Cache for Redis, Upstash, Redis Cloud).

For private CAs (self-hosted Redis, internal PKI), point Locke at the CA bundle:

```bash
KC_CACHE_REDIS_TLS_CA_FILE=/etc/ssl/redis/ca.crt
```

If the path is set but the file is missing or unreadable, **Locke refuses to start**.
This is intentional: silently falling back to the JVM truststore on a typo would be a
quiet security failure.

## Hostname verification

Hostname verification (CN/SAN match) is **on by default**. To opt out (e.g. a self-signed
cert whose CN doesn't match the K8s service DNS):

```bash
KC_CACHE_REDIS_TLS_VERIFY_HOSTNAME=false
```

The certificate chain is still validated against the trust anchors; only the
hostname match is skipped. The fully-insecure "trust any cert" mode is not exposed.

## Misconfiguration: refuses to boot

If any of the TLS knobs are set but the URL scheme is plain `redis://`, Locke refuses to
start with:

```
KC_CACHE_REDIS_TLS_* options are set but the connection URL scheme is `redis://`,
not `rediss://`. Either change the scheme to `rediss://` or unset the TLS options.
```

This catches the common mistake of setting `KC_CACHE_REDIS_TLS_CA_FILE` while forgetting
the second `s` in the URL: a configuration that today would silently send plaintext.

## Managed Redis matrix

| Service                       | Scheme to use            | CA file needed?         | Auth                                       |
| ----------------------------- | ------------------------ | ----------------------- | ------------------------------------------ |
| AWS ElastiCache (in-transit)  | `rediss://`              | No (public CA)          | `KC_CACHE_REDIS_PASSWORD` (auth token)     |
| AWS ElastiCache Cluster Mode  | `rediss-cluster://`      | No                      | `KC_CACHE_REDIS_PASSWORD`                  |
| Azure Cache for Redis         | `rediss://`              | No                      | `KC_CACHE_REDIS_PASSWORD` (primary key)    |
| Upstash Redis                 | `rediss://`              | No                      | `KC_CACHE_REDIS_PASSWORD`                  |
| Redis Cloud (Redis Ltd.)      | `rediss://`              | No                      | `KC_CACHE_REDIS_USERNAME` + `_PASSWORD`    |
| Self-hosted with private CA   | `rediss://`              | **Yes**                 | Whatever your cluster requires             |

## Not in this release

Planned Phase 2 follow-up:

- **mTLS** (client certificate + key): `KC_CACHE_REDIS_TLS_CLIENT_CERT_FILE` / `_KEY_FILE`.
- **AWS IAM auth token** for ElastiCache (rotating IAM-derived tokens).
- **AWS Secrets Manager** integration with credential rotation without a Keycloak restart.
- **TLS protocol / cipher version** controls.
- **Cert reload** without restart.

If any of those block your deployment, open an issue with your specific managed-Redis
target.
