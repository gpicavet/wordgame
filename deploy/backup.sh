#!/usr/bin/env bash
# Nightly SQLite backup, safe while the server runs. Cron: 15 3 * * * /opt/wordgame/backup.sh
set -euo pipefail

DB=/var/lib/wordgame/wordgame.db
DIR=/var/backups/wordgame
STAMP=$(date +%F)

mkdir -p "$DIR"
sqlite3 "$DB" ".backup '$DIR/wordgame-$STAMP.db'"
gzip -f "$DIR/wordgame-$STAMP.db"
find "$DIR" -name 'wordgame-*.db.gz' -mtime +14 -delete

# Off-site copy to Scaleway Object Storage, if an rclone remote named "scw" is configured.
if command -v rclone >/dev/null && rclone listremotes | grep -q '^scw:$'; then
    rclone copy "$DIR/wordgame-$STAMP.db.gz" scw:wordgame-backups/
fi
