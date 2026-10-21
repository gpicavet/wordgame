#!/usr/bin/env bash
# First install on a fresh Debian/Ubuntu Stardust instance, run as root:
#   scp server/build/libs/wordgame-server.jar dictionaries/bin/fr-libre.dawg deploy/* root@HOST:/tmp/wordgame/
#   ssh root@HOST bash /tmp/wordgame/install.sh
set -euo pipefail
SRC=/tmp/wordgame

apt-get update
apt-get install -y --no-install-recommends openjdk-17-jre-headless sqlite3 caddy

# 1 GB of RAM: a small swap file avoids an OOM kill during apt upgrades.
if ! swapon --show | grep -q /swapfile; then
    fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

id wordgame >/dev/null 2>&1 || useradd --system --home /opt/wordgame --shell /usr/sbin/nologin wordgame
mkdir -p /opt/wordgame /var/lib/wordgame
install -m 644 "$SRC/wordgame-server.jar" "$SRC/fr-libre.dawg" /opt/wordgame/
install -m 755 "$SRC/backup.sh" /opt/wordgame/
chown -R wordgame:wordgame /var/lib/wordgame

install -m 644 "$SRC/wordgame.service" /etc/systemd/system/
install -m 644 "$SRC/Caddyfile" /etc/caddy/Caddyfile
(crontab -l 2>/dev/null | grep -v backup.sh; echo "15 3 * * * /opt/wordgame/backup.sh") | crontab -

systemctl daemon-reload
systemctl enable --now wordgame
systemctl reload caddy
