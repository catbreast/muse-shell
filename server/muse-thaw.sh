#!/system/bin/sh
# muse-thaw v3 — keep the Muse shell app out of ColorOS Hans freezes.
# Every 5 s: clear the cgroup v2 freeze flag for the app's uid group and
# SIGCONT the app process if it sits in state T/t.
# v3 (2026-10-23): the uid is resolved dynamically each round
# (packages.list first, /data/data stat as fallback) — v2 hardcoded
# uid_10381, which a reinstall would silently invalidate.
PKG=app.ruisnow.muse
LOG=/data/adb/muse-thaw.log
PIDF=/data/adb/muse-thaw.pid
[ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null && exit 0
echo $$ > "$PIDF"
echo "$(date) thaw-watch v3 start pid=$$" >> "$LOG"
resolve_uid() {
  U=$(awk -v p="$PKG" '$1==p {print $2; exit}' /data/system/packages.list 2>/dev/null)
  case "$U" in ''|*[!0-9]*) U=$(stat -c %u "/data/data/$PKG" 2>/dev/null) ;; esac
  case "$U" in ''|*[!0-9]*) U="" ;; esac
  echo "$U"
}
while true; do
  APP_UID=$(resolve_uid)
  if [ -n "$APP_UID" ]; then
    for f in /sys/fs/cgroup/apps/uid_$APP_UID/cgroup.freeze /sys/fs/cgroup/apps/uid_$APP_UID/pid_*/cgroup.freeze; do
      if [ -f "$f" ] && [ "$(cat "$f" 2>/dev/null)" = "1" ]; then
        echo 0 > "$f" 2>/dev/null && echo "$(date) thawed v2 $f" >> "$LOG"
      fi
    done
  fi
  for p in $(pidof $PKG) $(pgrep -f $PKG); do
    [ -d "/proc/$p" ] || continue
    ST=$(grep -m1 "^State" /proc/$p/status 2>/dev/null | awk '{print $2}')
    if [ "$ST" = "T" ] || [ "$ST" = "t" ]; then
      kill -CONT $p 2>/dev/null && echo "$(date) CONT $p (was $ST)" >> "$LOG"
    fi
  done
  sleep 5
done
