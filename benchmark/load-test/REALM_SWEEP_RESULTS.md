# Progressive Realm Sweep — Definitive Results through 2000 Realms

**Framework:** SKYCF-392 · **Run:** SKYCF-393 · keycloak-benchmark dataset provider · both stacks 8 GB heap / 8 GB container / 4 GB Postgres / INFO logs

## TLDR (definitive run, 2026-05-16)

Clean run using the keycloak-benchmark **dataset provider** (server-side bulk create, the standard kcb method), both stacks given a 6 GB JVM heap so neither hits an artificial wall.

- **With adequate heap, neither OOMs at 2000 realms.** Vanilla finishes at 2.27 GiB (28% of 8 GB), Locke at 1.99 GiB (25%). The "vanilla OOMs at scale" story holds only at the *default* small heap (the separate out-of-the-box finding below).
- **Locke uses less heap at every scale ≥500.** Biggest gap at 1000 realms (1.42 vs 2.14 GiB, ~720 MiB / ~33% lighter). Narrower at 2000 (1.99 vs 2.27 GiB, ~280 MiB / ~12%). Heap snapshots are single point-in-time reads so GC timing adds noise; the consistent direction (Locke lower at every point) is the robust signal, not the exact percentage.
- **Locke's Redis stays flat at ~1.5-1.6 MB across all scales (100 → 2000).** This is the cleanest, unambiguous architectural result: the realm/user/authz cache never lands in Redis (iter-7 L1-only design), so the external store does not grow with realm count.
- **Provisioning degrades with scale on BOTH** (vanilla 1.25→0.30→0.28 r/s; Locke 1.00→0.54→0.37 r/s as realms grow 100→1000→2000). This is a shared, fundamental Keycloak + Postgres + realm-cache cost, not a backend difference and not a tooling artifact. Locke is modestly faster on the large batches.

### Definitive numbers

| Realms | Vanilla heap | Locke heap | Locke Redis |
|---|---|---|---|
| baseline | 992 MiB | 826 MiB | 1.52 MB |
| 100 | 1.08 GiB | 1.12 GiB | 1.54 MB |
| 500 | 1.44 GiB | 1.28 GiB | 1.54 MB |
| 1000 | 2.14 GiB | 1.42 GiB | 1.63 MB |
| 2000 | 2.27 GiB | 1.99 GiB | 1.60 MB |

### Honest caveats

- **SINGLE-INSTANCE ONLY (biggest limitation).** A and B were both 1 pod. In single instance, both Infinispan and Locke's L1 are just local memory, so the cache backend barely matters. The Infinispan-vs-Redis difference only appears under **clustering**, where the load balancer spreads requests across pods and the backends diverge: clustered Infinispan broadcasts invalidations over a JGroups N×N mesh and replicates session caches; Locke uses lightweight Redis pub/sub + shared Redis. **These single-instance numbers are not production-representative and likely understate the difference at cluster scale.** The decisive clustered run is SKYCF-426 (A3 3-pod vs B3 3-pod, needs EKS).
- Heap is a single snapshot after a 20 s settle, not a GC-averaged figure. Per-realm "slope" is noisy (both land roughly 0.6-0.7 MB/realm averaged over 2000; do not quote a clean "4x gentler" — that was a 1000-point artifact).
- This is **default-config caching** (vanilla realm cache unbounded, Locke L1 bounded at 10K). See the cache-bounding note; the apples-to-apples bounded-vs-bounded run is SKYCF-398.
- The strongest defensible claims: (1) flat external Redis regardless of realm count; (2) Locke consistently lighter on heap at scale; (3) provisioning slowdown is a shared Keycloak cost.

---

## Earlier run (2026-05-15, through 500, REST-loop provisioning) — superseded

Through 500 realms via the old REST-loop provisioner (since replaced by the dataset provider). Numbers here were affected by host memory pressure and are kept only for history. Use the definitive run above.

## Cache bounding — important methodology note

These results are **default config vs default config**. Verified in source (this Keycloak version, Infinispan 16):

- **Vanilla**: the `realms` / `users` / `authorization` *object* caches are **unbounded by default**. No `*_DEFAULT_MAX` constant; `CacheConfigurator.configureCacheMaxCount` only applies a cap if the operator sets `--cache-embedded-<name>-max-count`. Only the *revisions* caches are capped (realmRevisions 20000, userRevisions 100000, authorizationRevisions 20000; sessions 10000; keys/crl 1000). Source: `InfinispanConnectionProvider.java:42/46/57/58/66/70`, `CacheConfigurator.java:208`.
- **Locke**: L1 (Caffeine) bounded at **10,000 entries + 60s TTL** by default, configurable via `l1MaxEntries`. Source: `L1RedisCache.java:271`, `DefaultRedisConnectionProviderFactory.java:105`.

So the default-vs-default finding (vanilla grows with no plateau until OOM; Locke bounded) reflects real out-of-the-box behavior. A separate **bounded-vs-bounded** run is planned (**SKYCF-398**): cap vanilla with `--cache-embedded-realms-max-count=10000` so both evict at the same point, ideally at >10,000 realms so eviction actually triggers (needs EKS, SKYCF-395). That isolates the architectural difference (Infinispan invalidation+revision machinery + on-heap session/loginFailure caching) from the bound itself.

## Setup

- Provisioning: Admin REST API, parallel concurrency=8
- Per realm: 10 users, 5 clients, 2 IdP stubs
- A = vanilla Keycloak 26.3.5 (Infinispan, 2 GiB heap cap) · B = Locke (Redis 1-pod)
- Scales: 1, 10, 100, 500. Token-expiry bug fixed; 0 provisioning errors.

## Memory results (the solid finding)

| Realms | A heap (Infinispan) | B heap (Locke) | A % of 2 GiB cap | B Redis |
|---|---|---|---|---|
| baseline | 683 MiB | 753 MiB | 33% | 1.40 MB |
| 1 | 690 MiB | 761 MiB | 34% | 1.80 MB |
| 10 | 725 MiB | 774 MiB | 35% | 1.47 MB |
| 100 | 906 MiB | 854 MiB | 44% | 1.47 MB |
| 500 | **1.80 GiB** | **1.19 GiB** | **90%** | **1.58 MB** |
| Δ baseline→500 | **+1158 MiB** | **+466 MiB** | | +0.18 MB |
| Per realm | **~2.3 MiB** | **~0.95 MiB** | | flat |

### Interpretation

1. **Linear, not exponential.** Vanilla slope is ~2.2 MiB/realm at 0→100 and ~2.34 MiB/realm at 100→500. Locke is ~1.0 and ~0.91. Both predictable; Locke ~2.4x gentler.
2. **Vanilla breaking point ≈ 580-600 realms** with the default 2 GiB heap. At 500 it is already at 90% and GC pressure is climbing.
3. **Locke headroom ≈ 1400 realms** before the same ceiling, on identical hardware.
4. **Redis stays ~1.5 MB** across the whole sweep. The realm/user/authz cache never lands in Redis L2 (iter-7 L1-only design); growth is bounded Caffeine + JVM working set.
5. **Crossover near ~120 realms.** Locke starts ~70 MiB heavier (Redis client). Above ~120 realms vanilla overtakes it and the gap widens fast: at 500 realms vanilla uses **622 MiB more** than Locke.

## Provisioning speed (do NOT cite — unreliable here)

| | 100 realms | 500 realms |
|---|---|---|
| A (vanilla) | 1.36 r/s | 0.43 r/s |
| B (Locke) | 0.97 r/s | 0.17 r/s |

These contradict the earlier clean 100-realm run (where Locke was *faster*: 1.58 vs 1.27). Why this run is not trustworthy for a speed comparison:

- **Host memory-starved.** At measurement time the Mac had ~16 MB free RAM (heavy swap/compression). Lots of unrelated containers (k3d cluster, other apps) were resident.
- **Run-order bias.** The matrix always runs A first, then B. By the time B runs, the host has been loaded for 20+ minutes; B's Redis network round-trips suffer more under memory pressure than Infinispan's in-process path.
- **Conclusion:** provisioning speed needs isolated single-stack runs on an unloaded machine (or EKS) before any claim. **The earlier "+24% faster" was a single data point and is retracted pending a clean re-run.**

The memory measurements are point-in-time heap/Redis snapshots and are not affected by this; they remain valid.

## Status

- ✅ Clean 1/10/100/500 run for both stacks, 0 errors
- ✅ Memory curve validated through 500 realms, linear, Locke ~2.4x gentler
- ⚠️ Provisioning-speed comparison invalid on this host; needs isolated re-run
- ▶️ Next: 1000-realm run will push vanilla past its OOM point (expect vanilla failure/restart near ~580; Locke should survive). Run on an otherwise-idle machine for clean numbers.
- ⏳ 10,000 realms requires cloud infra (SKYCF-395)

## Reproduce (use an idle machine for trustworthy provisioning numbers)

```bash
cd benchmark/load-test/scripts
STACKS="A B" SCALES="1 10 100 500 1000" ./run-all-stacks.sh
```

## Raw data

`benchmark/load-test/results/2026-05-15/{A,B}/`
