# [Locke] Evaluate patch-overlay model vs vendored upstream history: should Locke stop vendoring Keycloak's source tree and carry only a patch series against a pinned tag?

## The question

SKYCF-537 asks whether Locke should move from its current model (`main` = upstream Keycloak
tag plus the Locke patch, continuously rebased, full upstream tree vendored) to a
patch-overlay model (the repo holds only `model/redis/` plus a patch series for the patched
upstream files, applied at build time against a pinned Keycloak tag), and to recommend
adopt, keep current, or hybrid.

Four sub-questions were asked and all four are answered below:

1. Does an overlay break or strengthen the "rebased against upstream daily" story in
   `WHY.md` / `README.md`?
2. How do the in-tree upstream-file patches translate to a patch series, and how often do
   they conflict on upstream churn?
3. Build mechanics: apply patches plus add `model/redis` at build time, versus the current
   Maven-module-in-tree approach.
4. Migration cost, and whether to sequence it before or after the Keycloak 27.x rebase
   (SKYCF-391).

Explicitly out of scope: this ticket is decision-only. No code, build, workflow, or config
change lands from it. The only artifacts are this document and its HTML twin. Implementation,
if the recommendation is accepted, is separate tickets (listed at the end).

## What we have today

**The shape of the fork.** `main` is an upstream Keycloak release commit with the Locke
patch replayed on top. Against the 26.7.1 base (`73f08b397f`, "Set version to 26.7.1"), the
Locke delta is 409 changed files: 377 new files that are entirely Locke's, and 22 modified
upstream source files plus 4 build/doc files. Broken down by area, the new files are
240 in `model/redis/`, 79 in `benchmark/`, 30 in `docs/`, 8 in `.github/`, 5 in `tests/`,
5 in `scripts/`, 3 in `testsuite/`, and 1 in `quarkus/`.

**The patched upstream surface** (the ticket estimated "~13"; the real number is 22 source
files plus 4 build files, and it has been exactly 22 across all six releases I measured):

- 18 files under `model/infinispan/src/main/java/`, almost all one-line `isSupported()`
  guards. Example: `InfinispanClusterProviderFactory.java:157` adds
  `&& !"redis".equals(config.root().get("cache"))`; `InfinispanUserSessionProviderFactory.java`
  adds the same guard plus a null-`ClusterProvider` early return. `CacheManager.java:219`
  null-guards `sendInvalidationEvents`. Fourteen of the eighteen are 7 changed lines or fewer.
- `quarkus/config-api/src/main/java/org/keycloak/config/CachingOptions.java` (+74/-2): adds
  `redis` to the mechanism enum (`CachingOptions.java:44`) and nine `cache-redis-*` options
  (`CachingOptions.java:193` onward).
- `quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/configuration/mappers/CachingPropertyMappers.java`
  (+51): nine property mappers appended to a list literal (`CachingPropertyMappers.java:170`
  onward) plus a `cacheSetToRedis()` predicate.
- `quarkus/deployment/src/main/java/org/keycloak/quarkus/deployment/KeycloakProcessor.java`
  (+23/-2): the conditional Jandex index build step (`KeycloakProcessor.java:824`) and the
  cluster health-check suppression (`KeycloakProcessor.java:846`).
- `quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/services/health/KeycloakClusterReadyHealthCheckProducer.java`
  (+2/-1).
- Build wiring: `model/pom.xml:39` (`<module>redis</module>`), `pom.xml:1048`
  (dependencyManagement entry), `quarkus/runtime/pom.xml:290` (the runtime dependency),
  `testsuite/model/pom.xml` (+44, the `jpa+redis` conformance profile), and
  `model/infinispan/pom.xml:130` (a `<skip>true</skip>` on upstream's network-dependent
  proto-lock check).

Total: about 700 diff lines across 22 files, against a 13,282-file repository.

**The release ritual.** `scripts/rebase-onto.sh` squashes every Locke commit into one
(`scripts/rebase-onto.sh:31-41`), rebases it onto the target tag
(`scripts/rebase-onto.sh:44`), bumps `model/redis/pom.xml`'s parent version
(`scripts/rebase-onto.sh:56`), strips upstream's inherited `dependabot.yml`
(`scripts/rebase-onto.sh:66-69`), then gates on `mvnw -pl model/redis -am compile`
(`scripts/rebase-onto.sh:74`) and `mvnw -pl quarkus/dist -am install`
(`scripts/rebase-onto.sh:96`). `.github/workflows/locke-bump.yml` wraps that, and its own PR
body states the friction the ticket describes: "This branch sits on the `$TARGET` upstream
tag, so its base diverges from main: do NOT merge via GitHub. Once CI is green, promote by
force-updating main (unlock/relock allow_force_pushes), then tag"
(`.github/workflows/locke-bump.yml:96`), followed by a literal
`git push --force-with-lease origin locke-$TARGET:main` (`locke-bump.yml:99`).

**The daily job.** `.github/workflows/locke-rebase-test.yml` runs at 06:00 UTC
(`locke-rebase-test.yml:12`), rebases onto the newest plain `X.Y.Z` upstream tag, and
auto-resolves every conflict outside a care-list regex by keeping Locke's side, because "a
rebase onto a different release always conflicts on `<version>` strings across poms; that is
noise" (`locke-rebase-test.yml:42-46`). It does not build and does not test. The care-list
(`locke-rebase-test.yml:46`) is the patch surface named above.

**The team already optimises for rebase cleanliness.** `.github/disable-upstream-workflows.sh:5-8`
explains that upstream's 18 workflow files are disabled through the GitHub API rather than
deleted, specifically so "the files stay byte-identical to upstream and rebases stay clean."

**How big the "spurious" PR diff actually is.** Measured, tag to tag:

| Bump | GitHub PR diff | Of which is pure upstream churn | Upstream commits |
|---|---|---|---|
| 26.6.1-1 to 26.6.2-1 | 334 files | 333 files | 41 |
| 26.6.2-1 to 26.6.3-1 | 515 files | 419 files | 60 |
| 26.6.3-1 to 26.6.4-1 | 318 files | 220 files | 16 |
| 26.6.4-1 to 26.7.0-1 | 3,930 files | 3,894 files | 877 |
| 26.7.0-1 to 26.7.1-1 | 268 files | 257 files | 24 |

So the complaint is real and quantified: on the 26.7.0 bump, 3,894 of 3,930 changed files
were upstream's, and Locke's own reviewable delta was about 36 files.

**How often the patch actually conflicts.** I extracted the patch series (the 22 upstream
files only) at each of six Locke releases and replayed each one onto the *next* release's
upstream base, which is exactly what a quilt-style refresh would face:

| Transition | `git apply` (strict) | `git apply --3way` | plain `patch` (quilt) |
|---|---|---|---|
| 26.6.1 to 26.6.2 | clean | n/a | clean |
| 26.6.2 to 26.6.3 | clean | n/a | clean |
| 26.6.3 to 26.6.4 | clean | n/a | clean |
| 26.6.4 to 26.7.0 | fails, 8 files | **clean** | 11 hunks FAILED, 21 fuzzed/offset |
| 26.7.0 to 26.7.1 | clean | n/a | clean |
| 26.7.1 to 26.7.2 (live) | **clean** | n/a | clean |
| 26.7.1 to upstream `main` @ ae1a3705, 2026-08-28 (live) | **clean** | n/a | clean, 0 failed hunks |

Zero human conflict resolutions across five real upstream bumps, including one minor-version
jump of 877 commits. The one hard case was resolved entirely by git's three-way merge, which
the current rebase model already gets for free and which plain `patch`/quilt could not do.
Underlying churn on the patched files is modest: over the 12 months to 2026-08-30 the busiest
patched file is `KeycloakProcessor.java` at 32 upstream commits, then
`InfinispanUserSessionProviderFactory.java` at 12 and
`DefaultInfinispanConnectionProviderFactory.java` at 11; the median patched file saw 3.

**Build mechanics today.** `model/redis` is a normal Maven module of upstream's reactor
(`model/pom.xml:39`), consumed by `quarkus/runtime` (`quarkus/runtime/pom.xml:290`) and
indexed conditionally at build time (`KeycloakProcessor.java:824`). CI builds
`./mvnw -pl model/redis -am install` (`locke-pr.yml:26`), runs upstream's own model
conformance suite against Redis with `./mvnw -pl testsuite/model -B test -Pjpa+redis`
(`locke-pr.yml:49`, the ADR-0004 parity gate), and releases with
`./mvnw -pl quarkus/dist -am install` (`locke-release.yml:42`).

**The public story.** `WHY.md:99-101` says Locke is "Not a hard fork. We carry a focused
patch set (a handful of upstream files plus a self-contained `model/redis/` module) on top of
upstream `main`, and a CI job rebases and tests against upstream daily." `README.md:83-85`
positions Locke's composite versioning as "the Percona Server / Amazon Corretto convention."

## Options

### Option A: keep the current model, unchanged (do-nothing)

**What it means.** Continue vendoring the full upstream tree, rebasing the squashed patch on
each release, force-updating `main` behind a manual branch-protection toggle, and living with
the cross-base PR.

**Effort.** Zero. **Cost.** Zero new spend. **Risk.** The force-push of a protected default
branch is a manual, unaudited step done a handful of times a year
(`locke-bump.yml:96-99`); a mistake there loses history on a public repo. Reviewing a bump
means reviewing a 268-to-3,930-file GitHub diff in which the signal is about 36 files, so in
practice the bump is not reviewed, it is trusted to CI.

**What it breaks.** Nothing new. It leaves the accuracy gap in `WHY.md:101` untouched: the
daily job checks patch applicability with everything outside the care-list auto-resolved, and
never builds or tests, so "rebases and tests against upstream daily" claims more than
`locke-rebase-test.yml` delivers. On a public receipts page that matters.

**Genuinely defensible?** Yes. The measured conflict cost is zero, and the two distributions
Locke names as its own pattern, Percona Server for MySQL and Amazon Corretto, both vendor the
full upstream source tree rather than keeping an overlay. This option is not embarrassing; it
is just leaving cheap wins on the table.

### Option B: full patch-overlay repo (Debian / Fedora / Brave model)

**What it means.** `sky-cloak/locke` holds `model/redis/`, `benchmark/`, `docs/`, the
`locke-*` workflows, a pinned upstream tag, and a `patches/` series. The build fetches
Keycloak at the pinned tag, applies the series, drops `model/redis` in, and runs Maven. A
version bump is a normal forward commit: change the pin, refresh the patches. No force-push,
no cross-base PR.

**Effort.** Large. Every one of the 7 `locke-*.yml` workflows and 5 `scripts/*.sh`
(about 1,500 lines) assumes an in-tree upstream reactor and has to be rewritten around a
materialise-then-build step, along with `benchmark/k8s/images/*` Dockerfiles,
`docker-compose-redis.yml`, and `quarkus/container` staging in
`locke-release.yml:56-69`. The `-Pjpa+redis` conformance gate depends on a patched
`testsuite/model/pom.xml`, so the overlay must patch the testsuite too and the full tree gets
materialised on every CI run regardless. Then the whole thing has to be proven to produce a
dist byte-equivalent to 26.7.1-1. Realistically 3 to 5 agent-days, plus one release cycle of
elevated risk.

**Cost.** No vendor spend. CI cost is *lower*, not higher: I measured a
`git clone --depth 1 --branch 26.7.1` of `keycloak/keycloak` at 6.0 seconds and 51 MB of
pack, versus the 545 MB of history the current `fetch-depth: 0` daily job pulls.

**Risk.** Three real ones. First, the series must be applied with git three-way, not quilt:
on the one hard bump in my sample, plain `patch` failed 11 hunks while `git apply --3way`
was clean, so the overlay has to keep both the old and new upstream tags fetched, which is a
subtlety quilt-style tooling does not have. Brave, the closest analogue, hits exactly this
and documents `git apply --3way` as the manual fallback on every Chromium bump. Second,
Keycloak publishes no source tarball with its releases (the 26.7.2 release assets are
distribution archives, docs, and npm tgz files only), so the build depends on either a git
fetch or GitHub's auto-generated archives, whose byte stability GitHub only promises with six
months' notice before a change. Third, `git blame` and IDE navigation across the upstream
sources go away for anyone working in the repo.

**What it breaks.** `git clone && ./mvnw install` stops working offline, which is a real
ergonomic and Apache-2.0-optics loss for a distribution whose pitch is "the code is
Keycloak." It also does not buy what the ticket hoped: conflict frequency is already zero, so
the overlay removes ceremony, not merge work.

### Option C: hybrid, keep the vendored tree and fix the ritual (recommended)

**What it means.** Three changes, none of which touch the source model:

1. Make the patch series a first-class artifact. A script emits
   `git diff <upstream-base> HEAD` restricted to the 22 patched upstream files, and a
   regenerated `patches/` directory ships with each release. This gives the overlay's real
   trust benefit ("here is precisely what Locke changes in Keycloak, in one directory,
   700 lines") without giving up a buildable tree.
2. Replace the daily rebase job with a daily patch-applicability check: shallow-clone the
   latest upstream release (6 seconds, 51 MB), `git apply --check` the series, fall back to
   `--3way`, open an issue only on real failure. This is cheaper and, unlike today's job, it
   actually means what `WHY.md` says. Optionally extend it to also apply against upstream
   `main` so 27.x-class breakage surfaces months early, which today's release-tag-only job
   cannot see.
3. Stop opening a cross-base PR into `main`. `locke-bump.yml` pushes `locke-<target>`, CI runs
   on that branch, and the review artifact posted is the Locke-delta diff (about 36 files,
   700 lines of upstream patch), not GitHub's meaningless 3,930-file cross-base view.
   Promotion becomes a small dispatched workflow that toggles `allow_force_pushes` through the
   API, force-updates with `--force-with-lease`, restores protection, and tags, so the one
   dangerous manual step becomes an audited scripted one.

**Effort.** Small. Roughly 1 to 1.5 agent-days across four S-sized tickets, all inside files
Locke already owns. No upstream file is touched, so nothing here enlarges the patch surface.

**Cost.** No vendor spend; slightly less CI than today.

**Risk.** Low. Each of the three is independently revertible, and none is on the critical path
of a release build.

**What it breaks.** Nothing. It does require editing `WHY.md:101` and the README to describe
what CI actually verifies, which is a small honesty improvement, not a weakening: "the patch
series is verified daily to still apply to the latest upstream Keycloak release, and every
release is built and conformance-tested against it" is both truer and stronger than the
current wording.

### Option D: merge upstream forward instead of rebasing (no force-push, keep the tree)

**What it means.** The obvious way to kill the force-push while keeping the vendored tree:
stop rebasing, and `git merge <upstream-tag>` into `main` on each bump. `main` moves forward
normally, branch protection stays on, GitHub can render and merge the PR natively.

**Effort/cost.** Would have been small. **Risk.** Fatal, and I measured it rather than
assuming it. Merging upstream 26.7.1's base into the 26.7.0-1 release, a *patch-level* bump,
conflicts in 190 files; merging 26.7.0's base into 26.6.4-1 conflicts in about 280 files,
including files Locke has never touched (`services/.../TokenManager.java`,
`themes/.../template.ftl`). Two causes, both structural: upstream's "Set version to X" commit
rewrites the `<version>` in every pom in the reactor, and Keycloak's release tags sit on
divergent release branches, so the merge base of Locke 26.6.4-1 and upstream 26.7.0 is a
commit from 2026-04-07, not the 26.6.4 tag.

**What it breaks.** It replaces a zero-conflict rebase with a 190-to-280-file manual merge on
every single release. Rejected on the evidence.

## Recommendation

**Adopt Option C (hybrid): keep the vendored tree and the rebase, and spend about a day
fixing the ritual around it.**

It wins because the ticket's premise, reasonable when written, does not survive measurement.
The overlay model exists to solve patch-conflict pain, and Locke does not have patch-conflict
pain: the 22-file, 700-line series applied strictly clean across five of six real upstream
transitions, needed zero human resolutions on the sixth, and applies strictly clean today to
both Keycloak 26.7.2 and upstream `main` as of 2026-08-28. Meanwhile every pain the ticket
actually names, the force-push, the branch-protection toggle, the 3,894-files-of-upstream-churn
PR, and GitHub's spurious conflict flags, is a property of how the bump is *presented and
promoted*, not of where the source lives. Option C removes all four for about a fifth of
Option B's cost and none of its risk, and it delivers the overlay's genuine benefit (a
readable, publishable statement of exactly what Locke changes) as a generated artifact.

The precedent also points this way. The distributions Locke names as its own model in
`README.md:85`, Percona Server for MySQL and Amazon Corretto, both maintain full vendored
source trees. The overlay examples in the ticket are OS packagers (Debian, Fedora), where the
overlay sits next to a build system that fetches upstream source as a first-class step and the
packager never needs to run upstream's own test reactor, and Brave, which pays for the model
with documented per-upgrade patch breakage.

**On the two sub-questions the recommendation turns on.** An overlay would *strengthen* the
`WHY.md` claim, but only because that claim is currently overstated: `locke-rebase-test.yml`
does not test, and auto-resolves everything outside the care-list. Option C strengthens it the
same amount by making the daily job a real, cheap applicability check and rewording the claim
to match. On sequencing versus SKYCF-391: do Option C first (it is a day, and it makes the
27.x bump itself easier to review), and do **not** delay 27.x for an overlay migration. The
series applies clean to upstream `main` today, so the 27.x risk is semantic, not textual: new
or changed SPI methods on the model interfaces, and upstream cache-architecture movement (its
most recent `main` commit as of 2026-08-28 is literally "Deprecate clusterless feature"). An
overlay reduces none of that. If we ever migrate, the moment to do it is *after* 27.x has
landed and the patch shape is known to be stable across a major boundary.

**What would change this:** evidence that the patch surface is about to grow or destabilise.
Concretely: if the 27.x rebase needs materially more than the current 22 upstream files (say
60+, or any patch reaching into `services/`), or if a bump requires more than a couple of
hours of manual conflict resolution, then per-file patch hygiene starts to matter more than
tree ergonomics and Option B becomes the better buy. The inverse also flips it: if upstream
lands a genuinely pluggable cache-*backend* SPI, the patch set mostly evaporates and both
options become moot. Note that discussion #48979, cited in `docs/adr/0004`, is not that: as
written it lets extensions declare caches that Infinispan then manages, which would not let
Locke substitute Redis for Infinispan.

**Weakest assumption:** that the near-zero conflict rate holds across a major version
boundary. My five measured transitions are all within 26.x, and there is no 27.x tag or
`release/27` branch upstream yet, so my strongest forward evidence is a single snapshot of
`main` at ae1a3705. If Keycloak 27 restructures the caching layer, the patch will need
rewriting rather than refreshing, and no repository model saves us from that. I am also
assuming that no consumer depends on `git clone && build` working offline; that is my read of
the project's own tooling, not something I verified with users.

## Sources

**External (all verified during this exploration):**

- Amazon Corretto 21 repository structure, a full OpenJDK source tree on the `develop` branch
  that "consumes development and patches to upstream openjdk/jdk21u":
  https://github.com/corretto/corretto-21/blob/develop/README.md and the top-level tree
  (`src/`, `make/`, `test/`) via https://api.github.com/repos/corretto/corretto-21/contents/?ref=develop
- Percona Server for MySQL development model, independent full-source branches, "instead of
  being a set of patches against an existing product, these branches are not related":
  https://docs.percona.com/percona-server/8.0/development.html and the full source tree
  (`sql/`, `storage/`, `plugin/`) via https://api.github.com/repos/percona/percona-server/contents/?ref=8.0
- Brave's Chromium upgrade process: pinned Chromium tag in `package.json`, patch application
  on `pnpm run init`, "there could be any number of patches that can't apply anymore",
  manual `git apply --3way --ignore-space-change --ignore-whitespace`, then
  `pnpm run update_patches`:
  https://github.com/brave/brave-core/blob/master/docs/chromium_version_upgrade.md
- Debian `3.0 (quilt)` source format, `debian/patches/` plus a `series` file, applied
  automatically by `dpkg-source`:
  https://www.debian.org/doc/manuals/maint-guide/dother.en.html
- GitHub source-archive byte stability, held stable "for no less than a year" from
  2023-02-21 with six months' notice before future format changes:
  https://github.blog/open-source/git/update-on-the-future-stability-of-source-code-archives-and-hashes/
- Keycloak 26.7.2 release assets (no source tarball published):
  https://api.github.com/repos/keycloak/keycloak/releases/latest
- Keycloak Cache SPI discussion, scoped to extensions declaring custom caches rather than
  replacing the cache backend: https://github.com/keycloak/keycloak/discussions/48979
- I could **not** price this in dollars. Both options are pure GitHub Actions time on an
  existing plan, and I have no access to the repository's Actions billing, so the CI
  comparison is stated in measured seconds and megabytes instead.
- I could **not** measure the current `locke-rebase-test.yml` wall-clock runtime, since I
  have no access to the workflow run history.

**Repository (this clone, branch `agent/locke-evaluate-patch-overlay-m`, HEAD `b52113385d`):**

- `WHY.md:99-101` (the "not a hard fork" and "rebases and tests against upstream daily" claim)
- `README.md:83-85` (composite versioning, "the Percona Server / Amazon Corretto convention")
- `.github/workflows/locke-bump.yml:92`, `:96`, `:99` (force-push, branch-protection toggle,
  "do NOT merge via GitHub")
- `.github/workflows/locke-rebase-test.yml:12`, `:42-46`, `:46` (daily cron, the
  version-string-noise rationale, the care-list regex defining the patch surface)
- `.github/workflows/locke-pr.yml:26`, `:47-49` (module build, `-Pjpa+redis` conformance gate)
- `.github/workflows/locke-release.yml:42`, `:56-69` (dist build, container staging)
- `.github/disable-upstream-workflows.sh:5-8` (upstream workflow files kept byte-identical so
  rebases stay clean)
- `scripts/rebase-onto.sh:31-41`, `:44-50`, `:56`, `:66-69`, `:74`, `:96` (squash, rebase,
  parent-version bump, dependabot strip, compile gate, dist gate)
- `model/pom.xml:39`, `pom.xml:1048`, `quarkus/runtime/pom.xml:290` (Maven wiring of
  `model/redis`)
- `quarkus/deployment/src/main/java/org/keycloak/quarkus/deployment/KeycloakProcessor.java:824`,
  `:846`, `:1194` (conditional Jandex index, health-check suppression)
- `quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/configuration/mappers/CachingPropertyMappers.java:170-215`
  (the nine Redis property mappers)
- `quarkus/config-api/src/main/java/org/keycloak/config/CachingOptions.java:44`, `:193`
  (the `redis` mechanism enum value and the `cache-redis` option prefix)
- `model/infinispan/pom.xml:130` (proto-lock check skip)
- `model/infinispan/src/main/java/org/keycloak/cluster/infinispan/InfinispanClusterProviderFactory.java:157`,
  `.../models/cache/infinispan/CacheManager.java:219` (representative guards)
- `docs/adr/0004-functional-parity-under-redis.md` (the parity invariant, the coverage test
  that replaced the hand-maintained guard list, and the Cache SPI convergence plan)
- Measurements were taken with `git diff`/`git log`/`git apply`/`patch` against the six
  release tags in this clone (`26.6.1-1`, `26.6.2-1`, `26.6.3-1`, `26.6.4-1`, `26.7.0-1`,
  `26.7.1-1`) and their upstream bases (`5bebfac09d`, `0a402f777f`, `8a67e82f8c`,
  `dc1bfc54bf`, `6c73e30278`, `73f08b397f`), plus live shallow clones of upstream
  `keycloak/keycloak` at tag `26.7.2` and `main` (`ae1a3705`, 2026-08-28).

## Open questions

None. The two things a founder might otherwise be asked to weigh in on are settled by the
evidence above: the sequencing question is answered (do the day of Option C work first, do not
delay SKYCF-391), and the "adopt or not" question turns on measured conflict rate rather than
on a business preference. The only judgment call left is whether to spend the day at all, and
that is the accept/reject of this document.

```tickets
[{"summary": "Publish the Locke patch series as a generated patches/ directory on every release", "type": "Task", "repo": "Locke", "why": "Gives the overlay model's real benefit, a one-directory statement of exactly what Locke changes in Keycloak, without giving up the buildable vendored tree."},
 {"summary": "Replace the daily rebase job with a shallow-clone patch-applicability check against the latest upstream release and main", "type": "Task", "repo": "Locke", "why": "The current daily job auto-resolves everything outside its care-list and never builds; a 6-second git apply --check is cheaper, is honest, and catches 27.x-class breakage months earlier by also checking upstream main."},
 {"summary": "Stop opening a cross-base bump PR; post the Locke-delta diff as the review artifact instead", "type": "Task", "repo": "Locke", "why": "GitHub cannot diff two different upstream bases meaningfully (3,894 of 3,930 files on the 26.7.0 bump were upstream churn), so the reviewable 700-line patch delta should be the review surface."},
 {"summary": "Script the main-branch promotion: API-toggle branch protection, force-with-lease, restore, tag", "type": "Task", "repo": "Locke", "why": "Turns the one manual unaudited force-push of a protected public default branch into a repeatable audited workflow step."},
 {"summary": "Correct WHY.md and README to describe what the daily upstream job actually verifies", "type": "Task", "repo": "Locke", "why": "WHY.md claims CI 'rebases and tests against upstream daily'; the job neither builds nor tests, and on a public receipts page the accurate claim is also the stronger one."}]
```
