#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
# Copies the English app screenshots of the screens already built into docs/design/captures/,
# where they replace the mockups. Run the screenshot tests first:
#
#   cd android && ./gradlew :app:testDebugUnitTest
#   android/scripts/update-design-captures.sh     (from the repository root)
#
# The mockups of the screens not built yet come from docs/design/mockups/render.py.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/android/app/build/outputs/roborazzi/en"
DST="$ROOT/docs/design/captures"
mapfile -t NAMES < <(grep -v '^#' "$ROOT/android/scripts/design-captures.txt")
missing=0
for n in "${NAMES[@]}"; do
  if [[ -f "$SRC/$n.png" ]]; then
    cp "$SRC/$n.png" "$DST/$n.png"
  else
    echo "missing: $SRC/$n.png" >&2
    missing=1
  fi
done
[[ $missing -eq 0 ]] && echo "Copied ${#NAMES[@]} screenshots to docs/design/captures/"
exit "$missing"
