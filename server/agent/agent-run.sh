#!/bin/bash
# root agent 监督循环：agent.py 退出就 10 秒后拉起。
cd "$(dirname "$0")"
while true; do
  python3 agent.py >> agent.log 2>&1
  echo "$(date '+%F %T') agent exited rc=$?, restart in 10s" >> agent.log
  sleep 10
done
