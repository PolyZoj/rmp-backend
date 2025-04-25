#!/bin/bash
set -e

PEER_HOST=$1   # other node’s hostname
ROLE=$2        # master or hot_standby

if [[ -z "$PEER_HOST" || -z "$ROLE" ]]; then
  echo "Usage: $0 <peer_host> <master|hot_standby>"
  exit 1
fi

export PGPASSWORD=$POSTGRES_PASSWORD

# First-time init?
if [ -z "$(ls -A "$PGDATA")" ]; then
  echo "[${ROLE}] Initializing fresh database..."
  # launch the official entrypoint in background
  /usr/local/bin/docker-entrypoint.sh postgres &
  child=$!

  # wait for postgres
  until pg_isready -h localhost -p 5432; do
    sleep 1
  done

  if [ "$ROLE" = "master" ]; then
    /home/init/init-master.sh
  else
    /home/init/init-standby.sh "$PEER_HOST"
  fi

  # stop init postgres
  pg_ctl -D "$PGDATA" -m fast -w stop

  echo "[${ROLE}] Bootstrap complete; starting postgres."
  exec /usr/local/bin/docker-entrypoint.sh postgres
fi

# Normal startup — but if master, check for timeline jump
if [ "$ROLE" = "master" ]; then
  export PGPASSWORD=$REPL_PASSWORD
  # can we connect to the peer?
  if pg_isready -h "$PEER_HOST" -p 5432 -U replicator; then
    CURRENT_TLI=$(pg_controldata "$PGDATA" | awk '/TimeLineID:/ {print $2}')
    NEW_TLI=$(psql -h "$PEER_HOST" -U replicator -Atc "SELECT timeline_id FROM pg_control_checkpoint();" || echo)

    if [[ "$NEW_TLI" =~ ^[0-9]+$ ]] && (( NEW_TLI > CURRENT_TLI )); then
      echo "[master] Timeline increased ($CURRENT_TLI → $NEW_TLI); running pg_rewind..."
      pg_ctl -D "$PGDATA" -m fast -w stop
      rm -f "$PGDATA"/postmaster.pid

      pg_rewind --target-pgdata="$PGDATA" \
        --source-server="host=$PEER_HOST port=5432 user=replicator password=$REPL_PASSWORD"

      # restore our configs
      cp /etc/postgresql/postgresql.conf "$PGDATA/postgresql.conf"
      cp /etc/postgresql/pg_hba.conf    "$PGDATA/pg_hba.conf"

      # switch to standby
      echo "standby.signal" > "$PGDATA/standby.signal"
      chown -R postgres:postgres "$PGDATA"
      echo "[master] pg_rewind done; now starting as standby."
    fi
  fi
fi

if [ -f "$PGDATA/standby.signal" ]; then
  echo "[entrypoint] Detected standby mode; launching auto_promote monitor."
  # pass the peer’s hostname so it knows whom to ping
  /home/scripts/auto_promote.sh "$PEER_HOST" &
fi

# finally, start normally
exec /usr/local/bin/docker-entrypoint.sh postgres
