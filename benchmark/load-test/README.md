# Progressive Realm Load-Test Framework

Tests how Keycloak (with Infinispan) and Locke (with Redis) scale as the number of realms grows.

## Stacks tested

| Stack | Cache | Pods | Redis mode | Port |
|---|---|---|---|---|
| A | Infinispan embedded | 1 | n/a | 18080 |
| A3 | Infinispan distributed | 3 | n/a | 18082 |
| B | Redis (Locke) | 1 | colocated | 18081 |
| B3 | Redis (Locke) | 3 | colocated | 18084 |
| C | Redis (Locke) | 1 | remote | 18083 |

## Scale points

- `1` — baseline
- `10` — small
- `100` — medium (Skycloak hosted typical customer = ~5-50 realms)
- `1000` — large self-hosted
- `10000` — enterprise/SaaS (deferred to a cloud/EKS run)

## Per-realm content (default)

- 10 users (override with `USERS_PER_REALM=50`)
- 5 clients (3 confidential + 2 public)
- 2 IdPs (1 OIDC stub + 1 SAML stub)
- All exercising the realm cache + admin cache paths

## What is measured

For each `(stack, scale)`:

- **Provisioning rate** (realms/sec via Admin REST API, parallel concurrency 8)
- **JVM heap** (used / max via `docker stats`)
- **Redis memory** (`used_memory_bytes`, `used_memory_human`, `DBSIZE` keys)
- **Realm count observed** (via Admin REST `/admin/realms`)
- **Cold-start login latency** (via kcb scenario against newly-created realm)
- **Warm p50/p95/p99/RPS** (30s kcb sustained load)

## Running

### Full matrix (all 5 stacks × all scales) — ~6-12 hours

```bash
cd benchmark/load-test/scripts
./run-all-stacks.sh
```

### Subset (faster)

```bash
# Only Locke vs vanilla, only small scales
STACKS="A B" SCALES="1 10 100" ./run-all-stacks.sh

# Single stack, full sweep
STACKS="B3" ./run-all-stacks.sh

# 50 users per realm (full-scale)
USERS_PER_REALM=50 STACKS="A B" SCALES="1 10 100" ./run-all-stacks.sh
```

### Single stack (assumes already running)

```bash
./run-progressive.sh A "1 10 100"
```

### Just provisioning

```bash
./provision-realms.sh http://localhost:18080 admin admin 1 100 10 5 2
```

## Results layout

```
benchmark/load-test/results/
└── 2026-05-15/
    ├── A/
    │   ├── metrics-A-baseline.json
    │   ├── metrics-A-realms-1.json
    │   ├── metrics-A-realms-10.json
    │   ├── metrics-A-realms-100.json
    │   ├── metrics-A-realms-1000.json
    │   ├── provision-10.log
    │   ├── provision-100.log
    │   ├── provision-1000.log
    │   ├── kcb-1.log
    │   ├── kcb-10.log
    │   ├── kcb-100.log
    │   └── kcb-1000.log
    ├── B/
    │   ├── ...
    └── ...
```

## Expected runtimes (local Docker, 16GB RAM Mac)

| Scale | Provision (parallel=8) | Memory after | Notes |
|---|---|---|---|
| 1 realm | <5s | ~500MB heap | smoke test |
| 10 realms | ~30s | ~800MB | quick |
| 100 realms | ~5 min | ~1.5-2GB | medium |
| 1000 realms | ~60-90 min | 4-8GB+ | overnight |
| 10000 realms | not feasible locally | est. 40GB+ | needs a cloud/EKS run |

## Known limitations

1. **Admin API rate limits** — Keycloak's admin REST has no global rate limit but contention on realm-create can cause 500s under high concurrency. Default `CONCURRENCY=8` is conservative.
2. **Network bottleneck on Docker bridge** — at 1000 realms, the bench client and KC share the same Docker network. May saturate.
3. **kcb scenario assumes a single realm** — current `AuthorizationCode` scenario uses one realm at a time. Future enhancement: randomize realm per virtual user.
4. **Memory growth shape** — only point measurements at each scale, not continuous timeseries. For Prometheus continuous capture (planned).
5. **Cold-start latency** — captured implicitly via kcb's first request. For dedicated cold-start measurement, do `docker restart <kc>` before kcb run (not in this framework yet).

## Interpreting results

Headline numbers to compare across stacks at each scale:

1. **Heap at 1000 realms** — does Infinispan local cache grow linearly? Does Locke's L1-only routing stay bounded by Caffeine `maximumSize=10000`?
2. **Provisioning rate** — does it degrade as `N` grows? Both stacks should plateau around N=100-1000 due to PostgreSQL contention.
3. **Redis memory at 1000 realms** — sessions go to Redis HSET; realm cache stays L1-only on Locke. Expect Redis to stay small until users log in.
4. **kcb latency at 1000 realms** — does p99 stay flat or does cache-miss-on-cold-realm increase tail latency?

## Open data

All raw JSON results are committed to the repo under `benchmark/load-test/results/<date>/`. Cross-stack comparison Markdown summary will be added once full matrix runs complete.

## TODO

- [ ] Add cross-stack comparison reporter (`report.py`)
- [ ] Add per-virtual-user realm randomization to kcb scenario
- [ ] Add Prometheus continuous capture variant
- [ ] Add EKS Terraform module
