#!/usr/bin/env bash
# Muse 壳中继保活，两层：
#  1) systemd 服务 muse-phone-relay 负责秒级自拉起；单元文件副本存本目录，
#     VM 被更换、/etc 丢失后，本脚本自动重装单元并 enable --now。
#  2) systemd 不可用时退回 setsid 直接拉起 server.py。
# 健康与自恢复静默；最终仍不健康才输出一行 FAIL（供 cron 汇报）。
export HOME=/home/hatch
DIR="$HOME/workspace/muse-android-shell/server"
LOG="$DIR/keepalive.log"
UNIT="muse-phone-relay"
UNIT_DST="/etc/systemd/system/$UNIT.service"
UNIT_SRC="$DIR/$UNIT.service"

exec 9>"$DIR/.keepalive.lock"
flock -n 9 || exit 0

token=$(python3 -c "import json;print(json.load(open('$DIR/tokens.json'))['admin'])" 2>/dev/null)
healthy() {
  curl -s -m 4 http://127.0.0.1:8390/status -H "X-Token: $token" 2>/dev/null | grep -q '"device"'
}

if healthy; then
  exit 0
fi

# 不健康：先确保 systemd 单元存在（VM 更换后 /etc 会丢）
if [ ! -f "$UNIT_DST" ] && [ -f "$UNIT_SRC" ]; then
  cp "$UNIT_SRC" "$UNIT_DST" 2>/dev/null && {
    systemctl daemon-reload 2>/dev/null
    systemctl enable "$UNIT" >/dev/null 2>&1
    echo "$(date '+%F %T') reinstalled systemd unit" >>"$LOG"
  }
fi

used_systemd=0
if [ -f "$UNIT_DST" ] && systemctl is-system-running >/dev/null 2>&1; then
  if systemctl is-active "$UNIT" >/dev/null 2>&1; then
    systemctl restart "$UNIT" 2>/dev/null && used_systemd=1
  else
    systemctl reset-failed "$UNIT" >/dev/null 2>&1
    systemctl start "$UNIT" 2>/dev/null && used_systemd=1
  fi
  [ "$used_systemd" = 1 ] && echo "$(date '+%F %T') systemd (re)started $UNIT" >>"$LOG"
fi

if [ "$used_systemd" = 0 ]; then
  if ! pgrep -f 'python3 server.py' >/dev/null; then
    cd "$DIR" && setsid --fork python3 server.py >>"$DIR/server.log" 2>&1
    echo "$(date '+%F %T') restarted server.py (setsid fallback)" >>"$LOG"
  fi
fi

for i in 1 2 3 4 5 6; do
  sleep 5
  if healthy; then
    exit 0
  fi
done

echo "FAIL: phone relay server.py 仍不健康（systemd 与直接拉起均未奏效）"
exit 1
