#!/usr/bin/env bash
# Disable every upstream Keycloak workflow on sky-cloak/locke at the REPO level,
# leaving only Locke's own (locke-*.yml) enabled.
#
# Why repo-level instead of deleting the files: deleting/moving the 18 upstream
# workflow files would conflict on every rebase onto upstream. `gh workflow
# disable` flips repo state instead, so the files stay byte-identical to upstream
# and rebases stay clean. Re-enable any of them later with `gh workflow enable`.
#
# Run once, after the first push to sky-cloak/locke main.
#   ./.github/disable-upstream-workflows.sh
set -euo pipefail
REPO="${REPO:-sky-cloak/locke}"

echo "Workflows on $REPO:"
gh workflow list -R "$REPO" --all

echo ""
echo "Disabling all non-Locke workflows..."
gh workflow list -R "$REPO" --all --json name,path,id \
  | python3 -c '
import json,sys
for w in json.load(sys.stdin):
    path=w.get("path","")
    name=w.get("name","")
    if "/locke-" in path:           # keep our own
        print(f"KEEP   {name} ({path})")
        continue
    print(f"DISABLE {name} ({path})")
'

# Actually perform it (filename is the stable handle for gh workflow disable)
for wf in $(gh workflow list -R "$REPO" --all --json path --jq '.[].path' | sed 's#.github/workflows/##'); do
  case "$wf" in
    locke-*.yml) echo "keep $wf" ;;
    *) gh workflow disable "$wf" -R "$REPO" 2>/dev/null && echo "disabled $wf" || echo "skip $wf (already off / not found)" ;;
  esac
done

echo ""
echo "Enabled workflows now:"
gh workflow list -R "$REPO"
