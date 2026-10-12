#!/bin/bash
# Prepares the database (idempotent), compiles the backend and starts the app.
set -euo pipefail

cd "$(dirname "$0")"
JDBC="$(ls lib/postgresql-*.jar | head -n 1)"

if [ "${SKIP_DB_SETUP:-0}" != "1" ]; then
    ./db/setup.sh || echo "Database setup did not complete — the app will start and report the problem."
fi

mkdir -p build
javac -cp "$JDBC" -d build src/hotel/*.java

exec java -cp "build:$JDBC" -Dapp.root="$(pwd)" hotel.HotelServer
