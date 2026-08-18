#!/usr/bin/env bash
# bump-release-versions.sh: update every release-critical version string for a new
# Locke release. Composite version required (e.g. 26.6.5-1).
#
#   ./scripts/bump-release-versions.sh 26.6.5-1
#
# Touches: CHANGELOG.md (new section stub), README.md (compat badge, quickstart
# image tags, compatibility table), docs/redis-security.md (image tags, version line).
# Leaves the changes uncommitted so they can be reviewed/amended.
set -euo pipefail

VERSION="${1:?usage: bump-release-versions.sh <locke-version, e.g. 26.6.5-1>}"
case "$VERSION" in
  *.*.*-*) ;;
  *) echo "ERROR: '$VERSION' is not a composite Locke version (upstream-build, e.g. 26.6.5-1)" >&2; exit 2 ;;
esac
UPSTREAM="${VERSION%-*}"
TODAY="$(date +%F)"
cd "$(dirname "$0")/.."

# CHANGELOG: insert a stub section right after the Keep-a-Changelog header line.
# The rebase case is the default text; edit before tagging if the release carries more.
perl -0pi -e "s{(based on \\[Keep a Changelog\\][^\\n]*\\n\\n)}{\${1}## [$VERSION] - $TODAY\n\n### Changed\n- Built from Keycloak $UPSTREAM. See the upstream release notes.\n\n}s" CHANGELOG.md

# README: compatibility badge, quickstart image tags, compat table (new current row,
# demote the previous current to superseded).
perl -pi -e "s{(img.shields.io/badge/Keycloak-)[0-9.]+(-blue)}{\${1}$UPSTREAM\${2}}" README.md
perl -pi -e "s{(ghcr.io/sky-cloak/locke:)[0-9.-]+( start-dev)}{\${1}$VERSION\${2}}g" README.md
perl -0pi -e "s{\\| \`([0-9.-]+)\` \\| ([0-9.]+) \\| current \\|}{| \`$VERSION\` | $UPSTREAM | current |\n| \`\$1\` | \$2 | superseded |}s" README.md

# docs/redis-security.md: image tags + the "available in Locke X" line.
perl -pi -e "s{(ghcr.io/sky-cloak/locke:)[0-9.-]+( start-dev)}{\${1}$VERSION\${2}}g" docs/redis-security.md
perl -pi -e "s{available in Locke \`[0-9.-]+\`}{available in Locke \`$VERSION\`}" docs/redis-security.md

echo "Bumped to $VERSION (upstream $UPSTREAM):"
git diff --stat -- CHANGELOG.md README.md docs/redis-security.md | sed 's/^/  /'
echo "Review the CHANGELOG stub, then commit with: git commit -am 'release: Locke $VERSION'"
