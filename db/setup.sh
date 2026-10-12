#!/bin/bash
# Creates the hotel database (if missing), applies the schema, and loads the
# report's starting rows only when every table is empty. Safe to re-run:
# it never drops, truncates or overwrites existing data.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
DB_NAME="${DB_NAME:-hotel_management}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-$(whoami)}"

PSQL=(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -v ON_ERROR_STOP=1 -q)

if ! "${PSQL[@]}" -d postgres -Atc "SELECT 1" >/dev/null 2>&1; then
    echo "Cannot reach PostgreSQL at $DB_HOST:$DB_PORT as $DB_USER." >&2
    echo "Start it with: brew services start postgresql@17" >&2
    exit 1
fi

if [ "$("${PSQL[@]}" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" != "1" ]; then
    echo "Creating database $DB_NAME"
    "${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$DB_NAME\""
else
    echo "Database $DB_NAME already exists"
fi

"${PSQL[@]}" -d "$DB_NAME" -f "$DIR/schema.sql"
echo "Schema is in place"

ROWS="$("${PSQL[@]}" -d "$DB_NAME" -Atc "
    SELECT (SELECT COUNT(*) FROM guest) + (SELECT COUNT(*) FROM room_type) + (SELECT COUNT(*) FROM room)
         + (SELECT COUNT(*) FROM department) + (SELECT COUNT(*) FROM employee) + (SELECT COUNT(*) FROM booking)
         + (SELECT COUNT(*) FROM payment) + (SELECT COUNT(*) FROM service) + (SELECT COUNT(*) FROM service_request)")"

if [ "$ROWS" = "0" ]; then
    "${PSQL[@]}" -d "$DB_NAME" --single-transaction -f "$DIR/seed.sql"
    echo "Loaded the starting records from the project report"
else
    echo "Found $ROWS existing rows — left untouched"
fi
