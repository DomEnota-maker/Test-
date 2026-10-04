#!/usr/bin/env bash
set -euo pipefail
apk="$(find "$GITHUB_WORKSPACE/emulator-input" -name '*.apk' -print -quit)"
test -n "$apk"
mkdir -p "$GITHUB_WORKSPACE/qa-evidence"
git -C "$GITHUB_WORKSPACE" rev-parse HEAD > "$GITHUB_WORKSPACE/qa-evidence/source-sha.txt"
APK="$apk" QA_OUT="$GITHUB_WORKSPACE/qa-evidence" \
  python3 "$GITHUB_WORKSPACE/qa/tem2_android_smoke.py"
if [[ -f "$GITHUB_WORKSPACE/app/src/main/assets/technical/diagnostic_framework_v2.json" ]]; then
  QA_OUT="$GITHUB_WORKSPACE/qa-evidence" \
    python3 "$GITHUB_WORKSPACE/qa/diesel_scheme_viewport_smoke.py" || status=1
  adb shell am force-stop ru.railbrake.calculator
  adb shell monkey -p ru.railbrake.calculator 1
  QA_OUT="$GITHUB_WORKSPACE/qa-evidence" \
    python3 "$GITHUB_WORKSPACE/qa/diagnostic_framework_v2_smoke.py" || status=1
fi
exit "${status:-0}"
