# Compatibility and support

## Versioning scheme

Locke uses **composite versioning**, the same convention as Percona Server and
Amazon Corretto: the Locke version is the upstream Keycloak version it is built
from, plus an independent build number.

```
  26.6.1 - 1
  └──┬──┘   └┬┘
 Keycloak   Locke build number
 version    (resets per Keycloak version)
```

`26.6.1-1` means "this is Keycloak 26.6.1, Locke build 1." A later Locke-only fix
on the same Keycloak base would be `26.6.1-2`. When we rebase onto Keycloak
26.7.0, the next release is `26.7.0-1`.

This makes the upstream version unambiguous from the Locke version alone, which
matters for matching CVE advisories and upstream release notes.

## Compatibility matrix

| Locke | Keycloak base | Container image | Status |
|---|---|---|---|
| `26.6.1-1` | 26.6.1 | `ghcr.io/sky-cloak/locke:26.6.1-1` | current |
| `26.3.5-1` | 26.3.5 | `ghcr.io/sky-cloak/locke:26.3.5-1` | previous |

## What "compatible" means

When `KC_CACHE=infinispan` (the default), Locke is byte-for-byte the upstream
Keycloak it was built from, plus the inert Redis modules on the classpath. All
upstream behavior, configuration, admin APIs, themes, and SPIs apply unchanged.

When `KC_CACHE=redis`, the cache SPIs are served by the Redis backend. Everything
outside the cache layer (database, tokens, admin REST, theming, federation) is
unchanged.

## Support window

- The **current** Locke line tracks the Keycloak version it is built from.
- Security fixes from upstream are rebased into the current line; see
  [SECURITY.md](./SECURITY.md).
- A longer LTS support policy is planned (tracked separately) and will be
  published here when finalized.

## Rebase cadence

A CI job rebases Locke onto upstream Keycloak `main` daily and runs the test
suite, so divergence is caught within hours rather than at release time. The
distribution's patch surface is intentionally small: a handful of upstream files
plus the self-contained `model/redis/` module.
