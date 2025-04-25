#!/bin/bash
set -e

# create replication user only if not exists and database only if not exists
psql -v ON_ERROR_STOP=1 --username "postgres" <<-EOSQL
DO
\$\$
BEGIN
  IF NOT EXISTS (
    SELECT FROM pg_catalog.pg_roles WHERE rolname = 'replicator'
  ) THEN
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$REPL_PASSWORD';
  END IF;
END
\$\$;

DO
\$\$
BEGIN
  IF NOT EXISTS (
    SELECT FROM pg_catalog.pg_database WHERE datname = '$POSTGRES_DB'
  ) THEN
    CREATE DATABASE $POSTGRES_DB;
  END IF;
END
\$\$;
EOSQL

# deploy our configs into PGDATA
cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

# mark as initialized
chown -R postgres:postgres "$PGDATA"
echo "[init-master] done."
