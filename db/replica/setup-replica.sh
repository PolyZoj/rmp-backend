#!/bin/bash
set -e

until pg_isready -h "$DB_HOST" -p "$DB_PORT"; do sleep 1; done

PGPASSWORD="$REPLICATOR_PASSWORD" pg_basebackup \
  -h "$DB_HOST" -D /var/lib/postgresql/data -U "$REPLICATOR_USER" \
  -Fp -Xs -P

cat >> /var/lib/postgresql/data/postgresql.conf <<EOF
hot_standby = on
max_wal_senders = 5
wal_level = replica
EOF

cat > /var/lib/postgresql/data/recovery.conf <<EOF
standby_mode = 'on'
primary_conninfo = 'host=$DB_HOST port=$DB_PORT user=$REPLICATOR_USER password=$REPLICATOR_PASSWORD sslmode=prefer'
trigger_file = '/var/lib/postgresql/data/failover.trigger'
EOF

chown -R postgres:postgres /var/lib/postgresql/data
chmod 600 /var/lib/postgresql/data/recovery.conf
