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
# Exit 0 only when every tag was read and every mirror matches GHCR at the end.
set -uo pipefail

SRC="${SRC:-ghcr.io/sky-cloak/locke}"
MIRRORS="${MIRRORS:-quay.io/skycloak/locke docker.io/skycloak/locke}"
REPO="${REPO:-sky-cloak/locke}"
SKOPEO="${SKOPEO:-skopeo}"
REPORT="${REPORT:-registry-report.md}"
DRY_RUN="${DRY_RUN:-0}"

problems=()
fixed=()
declare -A login_ok

write_report() {
  {
    echo "Registry guard: \`$SRC\` vs ${MIRRORS// /, }"
    echo
    if [ ${#problems[@]} -gt 0 ]; then echo "Still wrong:"; printf -- '- %s\n' "${problems[@]}"; echo; fi
    if [ ${#fixed[@]} -gt 0 ]; then echo "Repaired:"; printf -- '- %s\n' "${fixed[@]}"; echo; fi
    if [ ${#problems[@]} -eq 0 ] && [ ${#fixed[@]} -eq 0 ]; then echo "All tags match."; fi
  } > "$REPORT"
  cat "$REPORT"
}

sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-64; }

# Prints the raw-manifest digest, "absent" when the registry says the tag does not
# exist, or "error" for anything else (auth, rate limit, network), so a transient
# failure is never mistaken for a missing tag.
digest() {
  local out err
  out="$(mktemp)"; err="$(mktemp)"
  if $SKOPEO inspect --raw "docker://$1" > "$out" 2> "$err" && [ -s "$out" ]; then
    sha < "$out"
  elif grep -q 'manifest unknown' "$err"; then
    echo absent
  else
    echo error
  fi
  rm -f "$out" "$err"
}

if [ -z "${TAGS:-}" ]; then
  if ! releases="$(gh release list -R "$REPO" --limit 500 --json tagName,isDraft \
      --jq '.[] | select(.isDraft | not) | .tagName')"; then
    problems+=("could not list releases of \`$REPO\`; nothing was checked")
    write_report; exit 1
  fi
  TAGS="$(echo "$releases" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+-[0-9]+$' | sort -V | tr '\n' ' ')"
  if [ -z "$TAGS" ]; then
    problems+=("no release tags found in \`$REPO\`; nothing was checked")
    write_report; exit 1
  fi
  TAGS="$TAGS latest"
fi

if [ "$DRY_RUN" != 1 ]; then
  for mirror in $MIRRORS; do
    host="${mirror%%/*}"
    case "$host" in
      quay.io)   user="${QUAY_USER:-}"      token="${QUAY_TOKEN:-}" ;;
      docker.io) user="${DOCKERHUB_USER:-}" token="${DOCKERHUB_TOKEN:-}" ;;
      *)         user="" token="" ;;
    esac
    if [ -z "$user" ] || [ -z "$token" ]; then
      problems+=("\`$host\`: credentials not set, cannot repair")
    elif printf '%s' "$token" | $SKOPEO login "$host" -u "$user" --password-stdin >/dev/null 2>&1; then
      login_ok[$host]=1
    else
      problems+=("\`$host\`: login failed (token expired or revoked?)")
    fi
  done
fi

for tag in $TAGS; do
  want="$(digest "$SRC:$tag")"
  if [ "$want" = absent ] || [ "$want" = error ]; then
    problems+=("\`$SRC:$tag\` on GHCR (source): $want, nothing to copy")
    continue
  fi
  for mirror in $MIRRORS; do
    host="${mirror%%/*}"
    have="$(digest "$mirror:$tag")"
    if [ "$have" = "$want" ]; then echo "ok      $mirror:$tag"; continue; fi
    if [ "$have" = error ]; then
      echo "error   $mirror:$tag"
      problems+=("\`$mirror:$tag\` could not be read (auth, rate limit or network)")
      continue
    fi
    state=$([ "$have" = absent ] && echo missing || echo stale)
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

write_report
[ ${#problems[@]} -eq 0 ]
