#!/bin/bash
set -e

PEER_HOST=$1
if [ -z "$PEER_HOST" ]; then
  echo "Usage: $0 <master-host>"
  exit 1
fi

until pg_isready -h "$PEER_HOST" -U postgres; do
  echo "[init-standby] waiting for master..."
  sleep 2
done

echo -e "\n" >> etc/postgresql/postgresql.conf
echo -e "hot_standby = on \n" >> /etc/postgresql/postgresql.conf
echo -e "primary_conninfo = 'host=$PEER_HOST port=5432 user=replicator password=$REPL_PASSWORD' \n" >> /etc/postgresql/postgresql.conf

su postgres -c "pg_ctl -D $PGDATA -w stop"
echo "[init-standby] stopped postgres."

rm -rf "$PGDATA"/{*,.[!.]*,..?*}
echo "[init-standby] cleared PGDATA."

PGPASSWORD=$REPL_PASSWORD \
  pg_basebackup -h "$PEER_HOST" -D "$PGDATA" \
    -U replicator -v -P --wal-method=stream
echo "[init-standby] base backup complete."

chown -R postgres:postgres "$PGDATA"

cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

echo "standby.signal" > "$PGDATA/standby.signal"
