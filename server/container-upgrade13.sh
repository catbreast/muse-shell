#!/bin/bash
# In-place upgrade of the phone-local container: Debian bookworm -> trixie.
# Runs INSIDE the container (via enter.sh/linux.exec), detached, logs to
# /var/log/up13.log. Apt keeps existing config files (confold) so the
# Tsinghua mirror setup and DNS survive untouched apart from the release
# name swap in sources.list.
exec >> /var/log/up13.log 2>&1
echo "=== UP13 START $(date) ==="
sed -i 's/bookworm/trixie/g' /etc/apt/sources.list
grep '^deb' /etc/apt/sources.list
export DEBIAN_FRONTEND=noninteractive
apt-get update \
  && apt-get -y -o Dpkg::Options::="--force-confold" full-upgrade \
  && apt-get -y autoremove
echo "UP13_APT_RC=$?"
head -2 /etc/os-release
python3 --version
echo "=== UP13 DONE $(date) ==="
