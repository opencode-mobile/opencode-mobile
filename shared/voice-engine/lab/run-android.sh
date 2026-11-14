#!/bin/bash
# Runs speech model lab runs on an Android phone with a debug build of Hex Code installed, one after another.
#
#   run-android.sh <adb serial> "<model ids>|<options>|<clip set>" ...
#
# model ids: comma separated ids from models.json, or "all" for every downloaded model
# options:   empty, or comma separated: allCores, cpu | xnnpack | nnapi (the ONNX Runtime provider)
# clip set:  empty for the bundled technical clips, or the name of a set copied into files/lab-sets/
#
# Each run restarts the app, so a model that runs out of memory only loses its own run. Results collect in the
# app's external files folder: adb pull /sdcard/Android/data/dev.opencode.hexcode/files/lab/results.jsonl
set -u
s=$1
shift
ui() {
  adb -s $s shell rm -f /sdcard/ui.xml; adb -s $s shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s $s shell cat /sdcard/ui.xml 2>/dev/null
}
for run in "$@"; do
  IFS='|' read -r ids opts set <<< "$run"
  adb -s $s shell am start -S -n dev.opencode.hexcode/.MainActivity --es HexCodeLab "$ids" ${opts:+--es HexCodeLabOptions "$opts"} ${set:+--es HexCodeLabSet "$set"} >/dev/null
  t=0
  until ui | grep -q 'Lab run finished' || ! adb -s $s shell pidof dev.opencode.hexcode >/dev/null; do sleep 20; t=$((t+20)); [ $t -gt 2400 ] && echo "$s $run timeout" && break; done
  # the message line shows the last model that failed, if any
  echo "$s finished: $run"
  ui | grep -o 'text="[^"]*: [^"]*"' | grep -v "Lab run finished" | head -3
done
