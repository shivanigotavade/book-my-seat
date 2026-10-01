#!/bin/sh
# G18: one-command on-sale stampede. Usage:
#   ./burst.sh <BASE_URL>
#   BASE_URL=https://book-my-seat.onrender.com ./burst.sh
# Tuning via env: ADMIN_TOKEN SHOW_SEATS HOT_USERS HOT_SEAT STAMPEDE_REQUESTS
# HOT_SET STAMPEDE_USERS IDEM_RETRIES. Requires Java 21+ only.
set -e
BASE_URL="${1:-$BASE_URL}"
if [ -z "$BASE_URL" ]; then
  echo "usage: ./burst.sh <BASE_URL>" >&2
  exit 2
fi
exec java burst/Burst.java "$BASE_URL"
