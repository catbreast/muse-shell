#!/system/bin/sh
# MuseShell Linux container entry (Ubuntu 26.04 rootfs under /data/local/muse-linux).
# Runs the given command inside: private mount/ipc/uts namespaces + proc/sys/dev mounts + chroot.
# Usage: enter-ubuntu.sh [cmd [args...]]   (default: /bin/bash -l)
# Pushed to /data/local/muse-linux/enter-ubuntu.sh on the phone; this file is the source of truth.
BB=/data/adb/ksu/bin/busybox
BASE=/data/local/muse-linux
R=$BASE/rootfs-ubuntu

if [ ! -d "$R/etc" ]; then
  echo "ubuntu container not installed: $R missing" >&2
  exit 3
fi

if [ -z "$MUSE_UBUNTU_NS" ]; then
  export MUSE_UBUNTU_NS=1
  exec $BB unshare -m -u -i $BB sh "$0" "$@"
fi

# Ubuntu packages (systemd-resolved/resolvconf) keep replacing /etc/resolv.conf
# with a symlink into /run, which is a fresh tmpfs here -> DNS would die.
# Restore the plain file from the host-side canonical copy whenever needed.
if [ -L "$R/etc/resolv.conf" ] || ! grep -q nameserver "$R/etc/resolv.conf" 2>/dev/null; then
  rm -f "$R/etc/resolv.conf"
  cp "$BASE/resolv.conf" "$R/etc/resolv.conf" 2>/dev/null
fi

# Inside the private mount namespace: stop propagation back to the host.
$BB mount --make-rprivate / 2>/dev/null
$BB mount -t proc proc "$R/proc" 2>/dev/null
$BB mount -t sysfs sysfs "$R/sys" 2>/dev/null
$BB mount --bind /dev "$R/dev" 2>/dev/null
$BB mount -t devpts devpts "$R/dev/pts" 2>/dev/null
$BB mount -t tmpfs tmpfs "$R/dev/shm" 2>/dev/null
$BB mount -t tmpfs tmpfs "$R/run" 2>/dev/null
mkdir -p "$R/mnt/sdcard"
$BB mount --bind /sdcard "$R/mnt/sdcard" 2>/dev/null
hostname museubuntu 2>/dev/null

if [ $# -eq 0 ]; then set -- /bin/bash -l; fi
exec $BB chroot "$R" /usr/bin/env -i \
  HOME=/root TERM=xterm-256color \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  "$@"
