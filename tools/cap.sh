#!/usr/bin/env bash
# Screenshot of emulator-5560 (AVD PICO_6_1_Verified) -> .scratch/shots/<name>.png
#
# Default: host-side emulator console capture (`adb emu screenrecord screenshot <dir>`): a complete
# frame even while the emulator compositor idles at 2-3 fps. `--guest`: `pico-cli capture screenshot`.
set -u
DEV=${DEV:-emulator-5560}
AVD=${AVD:-PICO_6_1_Verified}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.scratch/shots/$1.png"
mkdir -p "$ROOT/.scratch/shots/emu"
avd="$(adb -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd'"; exit 2; fi

if [ "${2:-}" = "--guest" ]; then
  pico-cli capture screenshot --device $DEV --out "$OUT" >/dev/null 2>&1 && echo "OK $OUT" || echo "guest capture failed"
  exit 0
fi

WINDIR="$(cygpath -w "$ROOT/.scratch/shots/emu")"
before="$(ls -1 "$ROOT/.scratch/shots/emu" 2>/dev/null | wc -l)"
adb -s $DEV emu screenrecord screenshot "$WINDIR" >/dev/null 2>&1
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 0.5
  after="$(ls -1 "$ROOT/.scratch/shots/emu" 2>/dev/null | wc -l)"
  [ "$after" -gt "$before" ] && break
done
newest="$(ls -1t "$ROOT/.scratch/shots/emu"/Screenshot_*.png 2>/dev/null | head -1)"
if [ -z "$newest" ]; then echo "console screenshot failed"; exit 1; fi
sleep 0.5
mv -f "$newest" "$OUT"
echo "OK $OUT"
