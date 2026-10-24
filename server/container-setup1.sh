#!/bin/bash
# MuseShell container phase-1 setup: run INSIDE the chroot via enter.sh.
# Log lands in /root/setup1.log inside the container (= rootfs/root/setup1.log on host).
exec > /root/setup1.log 2>&1
set -x
rm -f /etc/resolv.conf
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /etc/resolv.conf
cat > /etc/apt/sources.list <<'EOF'
deb https://mirrors.tuna.tsinghua.edu.cn/debian bookworm main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian bookworm-updates main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bookworm-security main contrib non-free non-free-firmware
EOF
rm -f /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  python3 python3-pip curl ca-certificates procps iproute2 iputils-ping less file
python3 --version
echo SETUP1_DONE
