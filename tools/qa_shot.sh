#!/usr/bin/env bash
# One design state -> one screenshot: tools/qa_shot.sh S4 [extra debug cmds...]
#   tools/qa_shot.sh S3 "stroke expert" "pointer 0.62,0.4"
# Sends `state <id>`, waits for the STATE line in the log, runs the extra commands, lets the
# emulator render, then captures .scratch/shots/<id>.png (host console capture).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
id="$1"; shift
adb -s $DEV logcat -c
bash "$ROOT/tools/dev.sh" cmd state "$id" >/dev/null
i=0
until adb -s $DEV logcat -d -s Jieyan:V 2>/dev/null | grep -q "STATE $id " || [ $i -ge 20 ]; do sleep 0.5; i=$((i+1)); done
for c in "$@"; do
  # "cmd [word] [numbers]": the word goes to s=, the numbers to a=
  read -r name w1 w2 <<<"$c"
  word=""; nums=""
  for tok in "$w1" "$w2"; do
    [ -z "$tok" ] && continue
    if [[ "$tok" =~ ^[0-9.,-]+$ ]]; then nums="$tok"; else word="$tok"; fi
  done
  bash "$ROOT/tools/dev.sh" cmd "$name" "$word" "$nums" >/dev/null
done
# panels lag 3-8 s behind a state change on the emulator (QA round 1): wait 8 s by default
sleep "${SETTLE:-8}"
bash "$ROOT/tools/cap.sh" "$id"
adb -s $DEV logcat -d -s Jieyan:V 2>/dev/null | grep -E "STATE|RIG|STROKE|CMD" | tail -8 | cut -c1-200
