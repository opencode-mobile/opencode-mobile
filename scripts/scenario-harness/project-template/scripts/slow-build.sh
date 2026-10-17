#!/bin/sh
# Prints progress slowly so streaming shell output is visible.
i=1
while [ $i -le 20 ]; do
  echo "step $i/20: compiling module $i"
  sleep 0.5
  i=$((i + 1))
done
echo "build finished"
