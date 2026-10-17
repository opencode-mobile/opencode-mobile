#!/bin/sh
# Renders frames slowly; waits for .harness/continue and .harness/finish so a
# scenario can hold the tool open while the app shows growing output.
i=1
while [ $i -le 40 ]; do
  printf 'Frame %02d: rendered\n' "$i"
  i=$((i + 1))
done
while [ ! -f .harness/continue ]; do sleep 0.2; done
while [ $i -le 48 ]; do
  printf 'Frame %02d: rendered\n' "$i"
  sleep 0.25
  i=$((i + 1))
done
while [ ! -f .harness/finish ]; do sleep 0.2; done
echo "Diagnostics: no errors" >&2
echo "Render complete: 48 frames saved."
