#!/usr/bin/env bash
# Jieyan developer helper (Git Bash). Every device command is pinned to emulator-5560 (AVD
# PICO_6_1_Verified, the factory lane) and refuses to run when that serial is another AVD.
#
#   tools/dev.sh build                 assembleDebug            -> .scratch/build.log
#   tools/dev.sh fg                    which app is in front on the emulator
#   tools/dev.sh install | launch | stop | relaunch
#   tools/dev.sh cmd NAME [TEXT] [NUMBERS]     debug broadcast (see app/src/debug/.../DebugCommands.kt)
#   tools/dev.sh log [REGEX] [LINES]   logcat, tag Jieyan
#   tools/dev.sh crash                 last crash lines of the app
#   tools/dev.sh web                   forward the headset's :8765 to host :18765 (http://localhost:18765 in a browser)
DEV=${DEV:-emulator-5560}
AVD=${AVD:-PICO_6_1_Verified}
PKG=io.embodit.jieyan
TAG=Jieyan
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_SERIAL=$DEV ADB_SERIAL=$DEV PICO_CLI_DEVICE=$DEV
export JAVA_HOME='C:\Users\Admin\.pico\primer-cli\jdk\jdk-21.0.12.1+1'
export PICO_HOME='C:\Users\Admin\AppData\Local\PICO\sdk'
mkdir -p "$ROOT/.scratch"

check() {
  avd="$(adb -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

case "${1:-}" in
  build)
    cd "$ROOT" && ./gradlew.bat assembleDebug --no-daemon --console=plain > .scratch/build.log 2>&1
    echo "exit=$?"; grep -E '^e: |error:|BUILD|FAILED' .scratch/build.log | head -40 ;;
  fg)
    check; adb -s $DEV shell "dumpsys activity activities | grep -E 'topResumedActivity' | head -3" | tr -d '\r' ;;
  install)
    check; pico-cli app install "$ROOT/app/build/outputs/apk/debug/app-debug.apk" --device $DEV 2>&1 | tail -2 ;;
  launch)
    check; adb -s $DEV logcat -c; pico-cli app launch $PKG --device $DEV 2>&1 | tail -1 ;;
  stop)
    check; adb -s $DEV shell am force-stop $PKG; echo stopped ;;
  relaunch)
    check; adb -s $DEV shell am force-stop $PKG; sleep 1; adb -s $DEV logcat -c; pico-cli app launch $PKG --device $DEV 2>&1 | tail -1 ;;
  cmd)
    check
    extra=""
    [ -n "${3:-}" ] && extra="$extra --es s '$3'"
    [ -n "${4:-}" ] && extra="$extra --es a '$4'"
    adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd ${2:?cmd NAME} $extra" | tail -1 | tr -d '\r' ;;
  log)
    check; adb -s $DEV logcat -d -v time -s $TAG:V 2>/dev/null | grep -E -- "${2:-.}" | tail -n "${3:-200}" | cut -c1-600 ;;
  crash)
    check; adb -s $DEV logcat -d -v time 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PKG" | tail -n 40 | cut -c1-400 ;;
  web)
    # host port 18765 (8765 on the host is often taken by another session's forward)
    check; adb -s $DEV forward tcp:${HOSTPORT:-18765} tcp:8765 && echo "http://localhost:${HOSTPORT:-18765} -> $DEV:8765" ;;
  *) echo "usage: dev.sh build|fg|install|launch|stop|relaunch|cmd|log|crash|web" ;;
esac
