#!/system/bin/sh
# KernelSU service.d 开机自启：拉起容器内 root agent（muse-agent）。
# 安装位置：/data/adb/service.d/muse-agent.sh（chmod 755）。
LOGF=/data/local/muse-linux/agent-boot.log
(
  # 等容器骨架就绪
  for i in $(seq 1 60); do
    [ -f /data/local/muse-linux/enter.sh ] && break
    sleep 5
  done
  sleep 15
  echo "$(date) boot-start muse-agent" >> "$LOGF"
  /system/bin/sh /data/local/muse-linux/enter.sh /bin/bash \
    /home/hatch/workspace/muse-android-shell/server/agent/agent-run.sh \
    >> "$LOGF" 2>&1 &
) &
