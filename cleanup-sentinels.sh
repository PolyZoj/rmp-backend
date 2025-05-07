#!/usr/bin/env bash
set -euo pipefail

IMAGE="alpine:latest"

echo "Pulling ${IMAGE}…"
docker pull "${IMAGE}" >/dev/null

# Volumes to clean up
volumes="backend_psql_users_replica_data backend_psql_users_master_data"

for vol in $volumes; do
  echo "Looking for cleanup in volume $vol…"
  docker run --rm \
    -v "${vol}:/data" \
    "${IMAGE}" \
    sh -c 'find /data -type f -name ".initialized*" -print -delete' \
    && echo "Done" \
    || echo "Failed on $vol"
done
