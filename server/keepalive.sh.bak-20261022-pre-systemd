#!/usr/bin/env bash
# Muse 壳中继 server.py 保活：健康静默；死了自动拉起并复查；
# 拉起后仍不通才输出一行 FAIL（供 cron 汇报），其余情况退出码 0 且无输出。
export HOME=/home/hatch
DIR="$HOME/workspace/muse-android-shell/server"
LOG="$DIR/keepalive.log"

exec 9>"$DIR/.keepalive.lock"
flock -n 9 || exit 0

token=$(python3 -c "import json;print(json.load(open('$DIR/tokens.json'))['admin'])" 2>/dev/null)
healthy() {
  curl -s -m 4 http://127.0.0.1:8390/status -H "X-Token: $token" 2>/dev/null | grep -q '"device"'
}

if healthy; then
  exit 0
fi

# 不健康：先看进程在不在，统一按“拉起”处理（重复拉起靠端口占用自然失败，无副作用前先清残留）
if ! pgrep -f 'python3 server.py' >/dev/null; then
  cd "$DIR" && setsid --fork python3 server.py >>"$DIR/server.log" 2>&1
  echo "$(date '+%F %T') restarted server.py" >>"$LOG"
fi

for i in 1 2 3 4 5 6; do
  sleep 5
  if healthy; then
    exit 0
  fi
done

echo "FAIL: phone relay server.py 仍不健康（已尝试拉起，30 秒复查未通过）"
exit 1
