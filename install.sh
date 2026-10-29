#!/usr/bin/env bash
# Build Corner Player, install it on the TV, allow its corner window and, optionally, push cameras and a playlist.
#   TV_HOST=<tv ip> ./install.sh [config.json]   # {"cameras": [{"name": ..., "url": "rtsp://..."}], "playlist": "http://...", "remote": true, "token": "..."}
#   TV_HOST=<tv ip> APK=CornerPlayer-0.1.0.apk ./install.sh [config.json]   # a release APK instead of building
# Uses adb. Where macOS blocks the stock adb, set CLEANYOURTV to a clone of github.com/Gathin23/cleanyourtv with
# its tools set up (tools/venv), and its tools/tv.py is used instead.
set -euo pipefail
CONFIG="${1:-}"
if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
  CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
  if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
if [ -n "${APK:-}" ]; then
  [ -f "$APK" ] || { echo "no such file: $APK" >&2; exit 1; }
  APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
fi
cd "$(dirname "$0")"
PKG=io.github.maxhayim.cornerplayer

if [ -n "${CLEANYOURTV:-}" ]; then
  TOOLS="$(cd "$CLEANYOURTV/tools" && pwd)"
  PY=$TOOLS/venv/bin/python
  [ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
  export MSYS_NO_PATHCONV=1
  tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
  push() { "$PY" -I "$TOOLS/b64push.py" "$1" "$2" >/dev/null; }
else
  ADB="${ADB:-adb}"
  command -v "$ADB" >/dev/null || { echo "adb not found: install Android platform-tools, or set CLEANYOURTV" >&2; exit 1; }
  "$ADB" connect "$TV_HOST:5555" >/dev/null
  tv() { "$ADB" -s "$TV_HOST:5555" shell "$@"; }
  push() { "$ADB" -s "$TV_HOST:5555" push "$1" "$2" >/dev/null; }
fi

if [ -z "${APK:-}" ]; then
  [ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME" > local.properties; } || true
  if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
  ./gradlew assembleRelease -q --console=plain
  APK=app/build/outputs/apk/release/app-release.apk
fi

push "$APK" /data/local/tmp/corner-player.apk
OUT=$(tv 'pm install -r /data/local/tmp/corner-player.apk; rm -f /data/local/tmp/corner-player.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# Cameras and the playlist go in as a file through the app's provider (only the ADB shell may write it), so
# camera passwords never sit on a command line on the TV. The app reads it once and deletes it.
if [ -n "$CONFIG" ]; then
  STAGE=/data/local/tmp/corner-player-config.json
  push "$CONFIG" "$STAGE"
  tv "content write --uri content://$PKG.config/config.json < $STAGE; rm -f $STAGE; echo config pushed"
fi
# Display over other apps, for the corner window (many TVs hide the setting).
tv "appops set $PKG SYSTEM_ALERT_WINDOW allow; echo overlay allowed"
tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Corner Player is open on the TV. On the Clean Your TV launcher, press Home and answer Show card for its card."
