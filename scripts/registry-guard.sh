#!/usr/bin/env bash
# registry-guard.sh: check that every Locke release tag, and `latest`, is on every
# mirror registry with the same manifest digest as GHCR (the source of truth), and
# re-copy any that are missing or stale.
#
#   DRY_RUN=1 ./scripts/registry-guard.sh     # report only, no logins or copies
#
# Env:
#   SRC         source image            (default ghcr.io/sky-cloak/locke)
#   MIRRORS     space-separated mirrors (default quay.io + docker.io skycloak/locke)
#   REPO        GitHub repo for the release list (default sky-cloak/locke)
#   TAGS        override the tag list (default: every published release, plus latest)
#   SKOPEO      skopeo command (default skopeo)
#   QUAY_USER/QUAY_TOKEN, DOCKERHUB_USER/DOCKERHUB_TOKEN   mirror credentials
#   REPORT      markdown report path (default registry-report.md)
#
# Exit 0 when every mirror matches GHCR at the end, 1 otherwise.
set -uo pipefail

SRC="${SRC:-ghcr.io/sky-cloak/locke}"
MIRRORS="${MIRRORS:-quay.io/skycloak/locke docker.io/skycloak/locke}"
REPO="${REPO:-sky-cloak/locke}"
SKOPEO="${SKOPEO:-skopeo}"
REPORT="${REPORT:-registry-report.md}"
DRY_RUN="${DRY_RUN:-0}"

sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-64; }

# Digest of the raw manifest (the multi-arch index), or empty when the tag is absent.
# Piped straight into sha() so no byte of the manifest is altered.
digest() {
  local tmp; tmp="$(mktemp)"
  if $SKOPEO inspect --raw "docker://$1" > "$tmp" 2>/dev/null && [ -s "$tmp" ]; then
    sha < "$tmp"
  fi
  rm -f "$tmp"
}

creds_for() {
  case "$1" in
    quay.io/*)   echo "${QUAY_USER:-} ${QUAY_TOKEN:-}" ;;
    docker.io/*) echo "${DOCKERHUB_USER:-} ${DOCKERHUB_TOKEN:-}" ;;
  esac
}

if [ -z "${TAGS:-}" ]; then
  TAGS="$(gh release list -R "$REPO" --limit 500 --json tagName,isDraft \
    --jq '.[] | select(.isDraft | not) | .tagName' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+-[0-9]+$' | sort -V) latest"
fi

problems=()
fixed=()
declare -A login_ok

for mirror in $MIRRORS; do
  host="${mirror%%/*}"
  if [ "$DRY_RUN" != 1 ]; then
    read -r user token <<< "$(creds_for "$mirror")"
    if [ -z "${token:-}" ]; then
      problems+=("\`$host\`: no credentials set, cannot repair")
    elif $SKOPEO login "$host" -u "$user" --password "$token" >/dev/null 2>&1; then
      login_ok[$host]=1
    else
      problems+=("\`$host\`: login failed (token expired or revoked?)")
    fi
  fi
done

for tag in $TAGS; do
  want="$(digest "$SRC:$tag")"
  if [ -z "$want" ]; then
    problems+=("\`$SRC:$tag\` missing on GHCR (source), nothing to copy")
    continue
  fi
  for mirror in $MIRRORS; do
    host="${mirror%%/*}"
    have="$(digest "$mirror:$tag")"
    [ "$have" = "$want" ] && { echo "ok      $mirror:$tag"; continue; }
    state=$([ -z "$have" ] && echo missing || echo stale)
    echo "$state $mirror:$tag"
    if [ "$DRY_RUN" = 1 ] || [ -z "${login_ok[$host]:-}" ]; then
      problems+=("\`$mirror:$tag\` $state")
      continue
    fi
    if $SKOPEO copy --all "docker://$SRC:$tag" "docker://$mirror:$tag" >/dev/null 2>&1 \
        && [ "$(digest "$mirror:$tag")" = "$want" ]; then
      fixed+=("\`$mirror:$tag\` was $state, re-copied from GHCR")
    else
      problems+=("\`$mirror:$tag\` $state, re-copy failed")
    fi
  done
done

{
  echo "Registry guard: \`$SRC\` vs ${MIRRORS// /, }"
  echo
  if [ ${#problems[@]} -gt 0 ]; then echo "Still wrong:"; printf -- '- %s\n' "${problems[@]}"; echo; fi
  if [ ${#fixed[@]} -gt 0 ]; then echo "Repaired:"; printf -- '- %s\n' "${fixed[@]}"; echo; fi
  [ ${#problems[@]} -eq 0 ] && [ ${#fixed[@]} -eq 0 ] && echo "All tags match."
} > "$REPORT"
cat "$REPORT"

[ ${#problems[@]} -eq 0 ]
