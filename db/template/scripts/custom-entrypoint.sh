#!/bin/bash
set -e

PEER_HOST=${PEER_HOST:?need PEER_HOST}
ROLE=${ROLE:?need ROLE}

echo "[entrypoint] got \$1=$PEER_HOST"
echo "[entrypoint] got \$2=$ROLE"
echo "[entrypoint] got \$POSTGRES_PASSWORD=$POSTGRES_PASSWORD"

finish() {
  echo "[entrypoint] Starting PostgreSQL (pid $$)…"
  docker_entrypoint="/usr/local/bin/docker-entrypoint.sh postgres"
  $docker_entrypoint &
  child=$!
  wait "$child"
  exit 0
}

if [[ -z "$PEER_HOST" || -z "$ROLE" ]]; then
  echo "[entrypoint] Usage: $0 <peer_host> <master|hot_standby>"
  exit 1
fi

export PGPASSWORD=$POSTGRES_PASSWORD
export PGDATABASE=$POSTGRES_DB

SENTINEL="$PGDATA/.initialized_${ROLE}"

cleanup() {
  echo "🧹 Cleaning up sentinel file..."
  rm -f $PGDATA/.initialized*
}

trap cleanup SIGTERM SIGINT EXIT

# First-time init?
if [ ! -f "$SENTINEL" ]; then
  echo "[entrypoint] [${ROLE}] Initializing fresh database..."
  # launch the official entrypoint in background
  /usr/local/bin/docker-entrypoint.sh postgres &
  child=$!

  # wait for postgres
  until pg_isready -h localhost -p 5432 -U postgres; do
    echo "[entrypoint] [${ROLE}] waiting for postgres..."
    sleep 1
  done

  if [ "$ROLE" = "master" ]; then
    /home/init/init-master.sh
  elif [ "$ROLE" = "hot_standby" ]; then
    /home/init/init-standby.sh "$PEER_HOST"
  else
    echo "[entrypoint] [${ROLE}] Unknown role; exiting."
    exit 1
  fi

  kill "$child" 2>/dev/null || true
  wait "$child" 2>/dev/null || true

  touch "$SENTINEL"
  echo "[entrypoint] [${ROLE}] Bootstrap complete; starting postgres."
  finish
fi

# Normal startup — but if master, check for timeline jump
if [ "$ROLE" = "master" ]; then
  export PGPASSWORD=$REPL_PASSWORD
  # can we connect to the peer?
  if pg_isready -h "$PEER_HOST" -p 5432 -U replicator; then
    CURRENT_TLI=$(pg_controldata "$PGDATA" | grep '\sTimeLineID:' | sed 's/.*TimeLineID: *//')
    NEW_TLI=$(psql -h "$PEER_HOST" -U replicator -Atc "SELECT timeline_id FROM pg_control_checkpoint();" || true)
    echo "[entrypoint] [master] Current TLI: $CURRENT_TLI"
    echo "[entrypoint] [master] New TLI: $NEW_TLI"

    if [ "$NEW_TLI" -gt "$CURRENT_TLI" ]; then
      echo "[entrypoint] [master] Timeline increased ($CURRENT_TLI → $NEW_TLI); running pg_rewind..."

      pg_rewind --target-pgdata="$PGDATA" \
        --source-server="host=$PEER_HOST port=5432 user=replicator password=$REPL_PASSWORD"

      # restore our configs
      cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
      cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

      echo "primary_conninfo = 'host=$PEER_HOST port=5432 user=replicator password=$REPL_PASSWORD'" >> "$PGDATA/postgresql.conf"
      echo "hot_standby = on" >> "$PGDATA/postgresql.conf"

      # switch to standby
      echo "standby.signal" > "$PGDATA/standby.signal"
      chown -R postgres:postgres "$PGDATA"
      echo "[entrypoint] [master] pg_rewind done; now starting as standby."
    fi
  fi
  echo "[entrypoint] [master] Data directory exists. Starting normally."
fi

if [ -f "$PGDATA/standby.signal" ]; then
  echo "[entrypoint] Detected standby mode; launching auto_promote monitor."
  # wait some seconds before auto-promoting, otherwise we might promote before master has even started
  sleep 10
  # pass the peer’s hostname so it knows whom to ping
  /home/scripts/auto_promote.sh "$PEER_HOST" &
fi

finish
