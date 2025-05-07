#!/bin/bash

set -e
POSTGRES_USER=${POSTGRES_USER:?need POSTGRES_USER}
MASTER_HOST=${1:?Usage: $0 <master-host>}
CHECK_INTERVAL=5
ONLINE_SERVICES=("1.1.1.1" "google.com" "8.8.8.8")

 while true; do
   if ! pg_isready -h "$MASTER_HOST" -p 5432 -U "$POSTGRES_USER" -q; then
     echo "[auto_promote] master ($MASTER_HOST) down; checking internet..."

     for svc in "${ONLINE_SERVICES[@]}"; do
       if ping -c1 "$svc" >/dev/null 2>&1; then
         echo "[auto_promote] Internet OK; promoting standby..."
        PGPASSWORD=$POSTGRES_PASSWORD \
        psql -U "$POSTGRES_USER" -h localhost -p 5432 \
        -c "SELECT pg_promote(wait_seconds => 30);"
         exit 0
       fi
     done
     echo "[auto_promote] no internet, retrying in $CHECK_INTERVAL s."
   else
     echo "[auto_promote] master reachable."
   fi
   sleep $CHECK_INTERVAL
 done
