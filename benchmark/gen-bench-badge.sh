#!/usr/bin/env bash
# gen-bench-badge.sh (SKYCA-61).
# Reads the newest clustered parity result and emits:
#   benchmark/history/badge.json       (shields.io endpoint format, read by README)
#   benchmark/history/<date>-<sha>.json (one historical data point)
# Exits non-zero if parity < THRESHOLD so CI can open an alert issue.
#
#   ./gen-bench-badge.sh [threshold_percent]
set -euo pipefail
BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THRESH="${1:-90}"
HIST="$BENCH/history"
mkdir -p "$HIST"

parity_file=$(ls -t "$BENCH"/load-test/results/*/clustered-426/parity.txt 2>/dev/null | head -1 || true)
[ -z "${parity_file:-}" ] && { echo "no parity.txt found; run clustered-throughput-sweep.sh first"; exit 2; }

# parity.txt rows: "load_ups  A3_rps  B3_rps  parity_B/A". Take the highest-load row
# (saturation is the honest headline; low load already favors Redis).
worst_line=$(grep -E '^[0-9]' "$parity_file" | tail -1)
parity_pct=$(echo "$worst_line" | awk '{gsub("%","",$4); print $4}')
[ -z "$parity_pct" ] && { echo "could not parse parity from: $worst_line"; exit 2; }

# shields color thresholds
pct_int=${parity_pct%.*}
if   [ "$pct_int" -ge 95 ]; then color="brightgreen"
elif [ "$pct_int" -ge 90 ]; then color="green"
elif [ "$pct_int" -ge 80 ]; then color="yellow"
else color="red"; fi

cat > "$HIST/badge.json" <<JSON
{
  "schemaVersion": 1,
  "label": "throughput parity",
  "message": "${parity_pct}% of vanilla (3-pod)",
  "color": "${color}"
}
JSON

sha=$(git -C "$BENCH/.." rev-parse --short HEAD 2>/dev/null || echo "nogit")
date=$(date -u +%Y-%m-%d)
cat > "$HIST/${date}-${sha}.json" <<JSON
{
  "date": "${date}",
  "commit": "${sha}",
  "parity_pct": ${parity_pct},
  "detail_file": "$(basename "$parity_file")"
}
JSON

echo "badge: ${parity_pct}% (${color})  -> $HIST/badge.json"
echo "point: $HIST/${date}-${sha}.json"

awk -v p="$parity_pct" -v t="$THRESH" 'BEGIN{ if (p+0 < t+0){ printf "PARITY BELOW THRESHOLD: %.1f%% < %s%%\n", p, t; exit 1 } else { printf "parity OK: %.1f%% >= %s%%\n", p, t } }'
