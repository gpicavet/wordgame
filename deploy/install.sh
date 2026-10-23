#!/usr/bin/env bash
# Install or update on a Debian/Ubuntu VPS, as root (safe to run again):
#   scp server/build/libs/wordgame-server.jar dictionaries/bin/fr-libre.dawg deploy/* HOST:/tmp/wordgame/
#   ssh HOST sudo bash /tmp/wordgame/install.sh
set -euo pipefail
SRC=/tmp/wordgame

apt-get update
# default-jre-headless: Java 17 on Debian 12, 21 on Debian 13 and Ubuntu 24.04 (the jar targets 17).
apt-get install -y --no-install-recommends default-jre-headless sqlite3 caddy

# Small RAM: a swap file avoids an OOM kill during apt upgrades.
if ! swapon --show | grep -q /swapfile; then
    fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

id wordgame >/dev/null 2>&1 || useradd --system --home /opt/wordgame --shell /usr/sbin/nologin wordgame
mkdir -p /opt/wordgame /var/lib/wordgame
install -m 644 "$SRC/wordgame-server.jar" "$SRC/fr-libre.dawg" /opt/wordgame/
install -m 755 "$SRC/backup.sh" /opt/wordgame/
# Private extra words, only when provided.
if [ -f "$SRC/fr-2lettres.txt" ]; then install -m 644 "$SRC/fr-2lettres.txt" /opt/wordgame/; fi
chown -R wordgame:wordgame /var/lib/wordgame

# Firebase key (secret): readable by the service only, and only installed when provided.
if [ -f "$SRC/fcm-service-account.json" ]; then
    install -d -m 750 -o root -g wordgame /etc/wordgame
    install -m 640 -o root -g wordgame "$SRC/fcm-service-account.json" /etc/wordgame/
    rm -f "$SRC/fcm-service-account.json"
fi

install -m 644 "$SRC/wordgame.service" /etc/systemd/system/
install -m 644 "$SRC/Caddyfile" /etc/caddy/Caddyfile
# Nightly backup with a systemd timer: works on minimal images that ship without cron.
install -m 644 "$SRC/wordgame-backup.service" "$SRC/wordgame-backup.timer" /etc/systemd/system/

systemctl daemon-reload
systemctl enable --now wordgame
systemctl enable --now wordgame-backup.timer
systemctl reload caddy
