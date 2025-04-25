#!/bin/bash
set -e

# create replication user
psql -v ON_ERROR_STOP=1 \
  --username "postgres" \
  -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$REPL_PASSWORD';"

# your initial SQL
psql -v ON_ERROR_STOP=1 --username "postgres" \
  -c "CREATE DATABASE $POSTGRES_DB;"

# deploy our configs into PGDATA
cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

# mark as initialized
touch "$PGDATA/.initialized"
chown -R postgres:postgres "$PGDATA"
echo "[init-master] done."
