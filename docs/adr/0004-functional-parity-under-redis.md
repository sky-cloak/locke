# 4. Functional parity under KC_CACHE=redis

Status: Accepted (2026-07-01)

## Context

Locke runs Keycloak on either embedded Infinispan or Redis, chosen at boot. For Redis
mode, Locke disables the Infinispan provider factories (each `isSupported()` returns false
when `cache=redis`) and ships Redis-native replacements.

The disable step outran the replace step. `InfinispanPublicKeyStorageProviderFactory` (and
`InfinispanCrlStorageProviderFactory`) were guarded off under redis with no Redis equivalent
and no other fallback, so `session.getProvider(PublicKeyStorageProvider.class)` resolved to
null. Local login was unaffected, but external-IdP token verification (OIDC/SAML brokering)
and X.509 CRL revocation go through that provider, so they broke under redis. Reported by an
external user running an Entra ID broker on 26.6.4-1 (follow-up on Locke issue #40).

Root cause: a fork-and-guard model with no enforced coverage. The set of factories to guard
was tracked by hand (the rebase guide listed 7; there are ~17), so a provider could be
disabled without a replacement and ship silently.

Upstream Keycloak is independently reducing its Infinispan coupling: an experimental
cacheless mode that stores volatile data in the database (keycloak/keycloak#49469 and
siblings, labelled team/production-readiness), and a proposed pluggable Cache SPI (idea,
keycloak/keycloak discussion #48979) whose stated motivation is the same maintainability
pain Locke has.

## Decision

1. **Functional-parity invariant.** Every feature must remain functional under
   `KC_CACHE=redis`. An Infinispan provider factory may be disabled under redis only if
   another factory for the same SPI stays enabled under redis, either a Redis-native
   implementation or a working fallback (for example the JPA-backed OrganizationProvider).
   "Disabled with no enabled replacement" is forbidden. See docs/CONTEXT.md (functional
   parity).

2. **Enforcement by an automatic coverage test, not a hand-maintained list.** A test loads
   every provider-factory registration, evaluates `isSupported()` against a `cache=redis`
   config, and asserts that every SPI with at least one factory has at least one enabled
   under redis. It runs in the normal build, so a newly guarded-or-uncovered provider fails
   CI. A separate redis-boot integration test (Docker, on demand) resolves providers against
   a live server and exercises the broker and X.509 paths, catching the deeper case of an
   un-guarded Infinispan factory that reports enabled but fails at runtime because its
   Infinispan connection is gone.

3. **Degradation is visible.** A provider running under redis via a fallback (functional but
   uncached) logs a WARN at boot. Missing Redis-native caching is thus a tracked optimization
   backlog, never a silent correctness loss.

4. **Strategic direction.** Fork-and-guard is a stopgap. When the upstream Cache SPI lands,
   Locke should converge onto it (implement one clean SPI), which structurally removes this
   bug class. Engage discussion #48979 now with Locke's benchmarked evidence. Keep Locke's
   Redis backend positioned distinctly from upstream cacheless mode: Redis holds sub-100ms
   parity and sub-second failover without amplifying database write load; cacheless trades
   cache performance for DB-only operational simplicity. They serve different operators.

## Alternatives considered

- **Full caching parity** (a Redis-native cache for every Infinispan cache). Rejected:
  unbounded maintenance that chases upstream's cache layer on every rebase, the fork's single
  biggest risk, and it does not survive upstream evolution.
- **Static `isSupported` inventory only.** Rejected: it false-passes when an un-guarded
  Infinispan factory reports enabled but NPEs under redis. The runtime boot test covers that;
  the static test is the fast CI gate for the common (null-provider) case.
- **Keep the hand-maintained guard list.** Rejected: it went stale, which is how this
  shipped. The coverage test derives the set from the actual registrations.

## Consequences

- Immediate: `RedisPublicKeyStorageProvider` and `RedisCrlStorageProvider` ship in 26.6.4-2,
  backed by Locke's existing L1-only `keys` and `crl` caches (per-node Caffeine plus pub/sub
  invalidation, the correct shape for JWKS/CRL fetched with a TTL). The coverage test and the
  boot IT land with them.
- Every rebase, the coverage test flags any upstream cache-only provider Locke has not
  covered, as a red build rather than a customer report.
- The rebase guide's manual "factories to guard" list is superseded by the coverage test.
- When the Cache SPI lands, a follow-up migrates Locke from fork-and-guard to implementing
  the SPI.
