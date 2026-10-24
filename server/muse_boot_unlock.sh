#!/system/bin/sh
# BFU unlock daemon (installed 2026-10-22): before the first unlock after a
# reboot the MuseShell app cannot start, so this root daemon polls the
# Cloudflare relay for a boot.unlock command and performs one PIN unlock
# using the PIN stored in /data/adb/muse-boot/relay.conf (root-only, 600).
# Mission ends at first unlock or after 30 minutes. Non-boot commands are
# answered with an error result so callers never hang.
CONF=/data/adb/muse-boot/relay.conf
[ -f "$CONF" ] || exit 0
. "$CONF"
[ -n "$BASE" ] && [ -n "$PHONE_TOKEN" ] && [ -n "$PIN" ] || exit 0

locked() { dumpsys trust 2>/dev/null | grep -m1 "deviceLocked=1" >/dev/null 2>&1; }
post_result() {
  curl -s -m 15 -X POST -H "X-Token: $PHONE_TOKEN" -H "X-Device: ${DEV}-boot" \
    -H "Content-Type: application/json" -d "$1" "$BASE/result" >/dev/null 2>&1
}

(
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 2; i=$((i+1)); done
  sleep 3
  locked || exit 0
  END=$(( $(date +%s) + 1800 ))
  while [ "$(date +%s)" -lt "$END" ]; do
    locked || break
    OUT=$(curl -s -m 32 -H "X-Token: $PHONE_TOKEN" -H "X-Device: ${DEV}-boot" -w '\n%{http_code}' "$BASE/poll" 2>/dev/null)
    CODE=$(echo "$OUT" | tail -n 1)
    [ "$CODE" = "200" ] || { sleep 5; continue; }
    BODY=$(echo "$OUT" | sed '$d')
    ID=$(echo "$BODY" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    TOOL=$(echo "$BODY" | grep -o '"tool":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    [ -n "$ID" ] || continue
    if [ "$TOOL" != "boot.unlock" ]; then
      post_result "{\"id\":\"$ID\",\"ok\":false,\"error\":\"bfu_daemon_only\"}"
      continue
    fi
    SZ=$(wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | head -n 1)
    W=${SZ%x*}; H=${SZ#*x}; [ -n "$W" ] || { W=1264; H=2780; }
    X=$((W/2)); Y1=$((H*85/100)); Y2=$((H*35/100))
    input keyevent 224; sleep 1
    input swipe $X $Y1 $X $Y2 200; sleep 1
    i=1
    while [ $i -le ${#PIN} ]; do
      d=$(echo "$PIN" | cut -c $i)
      input keyevent $((7+d))
      i=$((i+1))
    done
    sleep 1; input keyevent 66; sleep 2
    if locked; then OK=false; else OK=true; fi
    post_result "{\"id\":\"$ID\",\"ok\":$OK,\"result\":{\"unlocked\":$OK,\"via\":\"bfu-daemon\"}}"
    [ "$OK" = "true" ] && break
  done
) &
