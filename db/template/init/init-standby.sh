#!/bin/bash
set -e

PEER_HOST=$1
if [ -z "$PEER_HOST" ]; then
  echo "Usage: $0 <master-host>"
  exit 1
fi

# only bootstrap if truly empty
if [ -z "$(ls -A "$PGDATA")" ]; then
  echo "[init-standby] Bootstrapping from $PEER_HOST..."

  # wait for master ready
  until pg_isready -h "$PEER_HOST" -U postgres; do
    echo "[init-standby] waiting for master..."
    sleep 2
  done

  # copy our base configuration
  cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
  cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

  # do the base backup
  PGPASSWORD=$REPL_PASSWORD \
    pg_basebackup -h "$PEER_HOST" -D "$PGDATA" \
      -U replicator -v -P --wal-method=stream

  # enable standby mode
  echo "standby.signal" > "$PGDATA/standby.signal"

  chown -R postgres:postgres "$PGDATA"
  echo "[init-standby] base backup complete."
fi

# mark as initialized
touch "$PGDATA/.initialized"
