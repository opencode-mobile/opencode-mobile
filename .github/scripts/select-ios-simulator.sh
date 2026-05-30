#!/usr/bin/env bash
set -euo pipefail

device_id="$(
  python3 - <<'PY'
import json
import subprocess
import sys

payload = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))
devices = payload["devices"]

for runtime in sorted(devices.keys(), reverse=True):
    if "iOS" not in runtime:
        continue
    for device in devices[runtime]:
        if device.get("isAvailable") and "iPhone" in device["name"]:
            print(device["udid"])
            sys.exit(0)

sys.exit("No available iPhone simulator found")
PY
)"

echo "destination=platform=iOS Simulator,id=${device_id}" >> "${GITHUB_OUTPUT}"
