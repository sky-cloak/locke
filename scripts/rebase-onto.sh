#!/usr/bin/env bash
# rebase-onto.sh: rebase the Locke patch set onto a target upstream Keycloak tag.
#
# Automates every mechanical step (fetch, squash, rebase, version bump, build) and
# stops with a precise, machine-parseable checklist at the two gates that need a
# human: git merge conflicts, and new/changed upstream SPI methods.
#
#   ./scripts/rebase-onto.sh 26.7.0 [source-branch]
#
# Env:
#   UPSTREAM_REMOTE  remote pointing at keycloak/keycloak (default: origin)
#
# Exit codes (consumed by .github/workflows/locke-bump.yml):
#   0  success: locke-<target> builds clean
#   3  git rebase conflict (human must resolve)
#   4  compile failure, almost always new upstream SPI methods (human must implement)
#   5  dist build failure (deeper breakage)
set -uo pipefail

TARGET="${1:?usage: rebase-onto.sh <keycloak-version> [source-branch]}"
SRC="${2:-main}"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-origin}"
WORK="locke-${TARGET}"
INTERFACES="RealmModel UserModel ClientModel RoleModel GroupModel ClientScopeModel UserLoginFailureModel ClientSessionModel UserSessionModel"

log(){ echo "[rebase-onto] $*"; }

log "fetching upstream tag ${TARGET} from ${UPSTREAM_REMOTE}"
git fetch --no-tags "${UPSTREAM_REMOTE}" "tag" "${TARGET}" || { echo "::ERROR:: cannot fetch tag ${TARGET}"; exit 2; }

# Locke patch base = parent of the first commit that introduced model/redis.
FIRST_REDIS=$(git log --reverse --format='%H' "${SRC}" -- model/redis | head -1)
[ -z "${FIRST_REDIS}" ] && { echo "::ERROR:: no model/redis commits on ${SRC}"; exit 2; }
BASE=$(git rev-parse "${FIRST_REDIS}^")
log "patch base = ${BASE:0:12}; squashing $(git rev-list --count "${BASE}..${SRC}") Locke commits"

# Fresh work branch carrying the whole Locke patch as one commit.
git branch -f "${WORK}" "${SRC}"
git checkout "${WORK}"
git reset --soft "${BASE}"
git commit -q -m "Locke patch set (squashed for ${TARGET})"

log "rebasing onto ${TARGET}"
if ! git rebase --onto "${TARGET}" "${BASE}" "${WORK}"; then
  echo "::CONFLICT:: rebase hit conflicts in:"
  git diff --name-only --diff-filter=U | sed 's/^/  /'
  echo "Resolve each, 'git add' them, run 'git rebase --continue', then re-run the"
  echo "build steps (steps 'bump' + 'compile' + 'dist' below) by hand or re-invoke after committing."
  exit 3
fi

log "bumping model/redis/pom.xml parent version to ${TARGET}"
perl -0pi -e "s{(<artifactId>keycloak-model-pom</artifactId>\s*<groupId>org\.keycloak</groupId>\s*<version>)[^<]+(</version>)}{\${1}${TARGET}\${2}}s" model/redis/pom.xml
git add model/redis/pom.xml
git commit -q --amend --no-edit

log "compiling model/redis against ${TARGET}"
if ! ./mvnw -q -pl model/redis -am -DskipTests compile 2>/tmp/locke-bump-compile.log; then
  echo "::NEWMETHODS:: model/redis did not compile. Most likely new/changed upstream SPI methods."
  echo ""
  echo "Methods ADDED to interfaces Locke implements ($BASE -> $TARGET):"
  for iface in ${INTERFACES}; do
    f="server-spi/src/main/java/org/keycloak/models/${iface}.java"
    added=$(git diff "${BASE}" "${TARGET}" -- "${f}" 2>/dev/null \
      | grep -E '^\+' | grep -vE '^\+\+\+' \
      | grep -E '\b(void|int|long|boolean|String|Stream|List|Map|Set|Optional)\b.*\(.*\);' \
      | sed 's/^+//;s/^[[:space:]]*//')
    [ -n "${added}" ] && { echo "  ### ${iface}:"; echo "${added}" | sed 's/^/    /'; }
  done
  echo ""
  echo "Compiler errors:"
  grep -E "does not override|cannot find symbol|incompatible types|ERROR.*\.java:" /tmp/locke-bump-compile.log | head -25 | sed 's/^/  /'
  echo ""
  echo "Implement the methods on the matching adapters (e.g. RealmModel -> RealmAdapter + entities/CachedRealm),"
  echo "mirroring model/infinispan, then commit on ${WORK} and re-run the build."
  exit 4
fi

log "building dist against ${TARGET}"
if ! ./mvnw -q -pl quarkus/dist -am -DskipTests install 2>/tmp/locke-bump-dist.log; then
  echo "::DISTFAIL:: model/redis compiled but the dist build failed (Quarkus wiring or packaging)."
  grep -E "ERROR|BUILD FAILURE" /tmp/locke-bump-dist.log | head -25 | sed 's/^/  /'
  exit 5
fi

echo "::SUCCESS:: ${WORK} builds against Keycloak ${TARGET}."
echo "Release it with:"
echo "  git push <remote> ${WORK}"
echo "  git tag -a ${TARGET}-1 -m 'Locke ${TARGET}-1' && git push <remote> ${TARGET}-1"
