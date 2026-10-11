#!/usr/bin/env bash
# Start ONLY this prepared project's previously initialized local database.
# New-server initialization/credential provisioning is a separate, explicit step.
# Never initialize insecurely, fall back to old global data, or stop a listener.
set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepared-local-config.sh
source "$ROOT_DIR/scripts/prepared-local-config.sh"
BASE="${PREPARED_MYSQL_BASEDIR:-${HOME:?Set PREPARED_MYSQL_BASEDIR}/.local/mysql-8.4.3}"
DATA="$ROOT_DIR/.runtime/mysql"
SOCK="$DATA/mysql.sock"
PID_FILE="$DATA/server.pid"
PREPARED_FRESH_POLICY=''
PREPARED_FRESH_MARKER="$ROOT_DIR/.runtime/mysql-bootstrap.complete"
MYSQL_PORT="${PREPARED_MYSQL_PORT:-13317}"
prepared_validate_port PREPARED_MYSQL_PORT "$MYSQL_PORT"
if [[ "$MYSQL_PORT" == "$PREPARED_BACKEND_PORT" || "$MYSQL_PORT" == "$PREPARED_FRONTEND_PORT" ]]; then
  echo 'Prepared MySQL and application ports must be different.' >&2
  exit 1
fi

for prepared_required in PREPARED_DB_JDBC_URL PREPARED_DB_USERNAME PREPARED_DB_PASSWORD; do
  if [[ -z "${!prepared_required:-}" ]]; then
    echo "Missing $prepared_required; legacy database variables are not used." >&2
    exit 1
  fi
done
if [[ ! "$PREPARED_DB_JDBC_URL" =~ ^jdbc:mysql://127\.0\.0\.1:$MYSQL_PORT/prepared_interview(_[a-z0-9]+)*(\?.*)?$ ]]; then
  echo 'Prepared local MySQL requires the matching loopback port and isolated prepared_interview schema.' >&2
  exit 1
fi

if [[ -L "$ROOT_DIR/.runtime" || -L "$DATA" || -L "$DATA/mysql" || -L "$DATA/mysql.ibd" || -L "$PID_FILE" ]]; then
  echo 'Prepared runtime/database control paths must not be symbolic links.' >&2
  exit 1
fi
if [[ ! -d "$DATA/mysql" || ! -f "$DATA/mysql.ibd" || "$(cd -P "$DATA" && pwd)" != "$DATA" ]]; then
  echo 'Independent prepared MySQL is not initialized; do not reuse or initialize an old directory.' >&2
  exit 1
fi
if [[ ! -x "$BASE/bin/mysql" || ! -x "$BASE/bin/mysqld" ]]; then
  echo 'Prepared MySQL binaries are unavailable; configure PREPARED_MYSQL_BASEDIR.' >&2
  exit 1
fi
BASE="$(cd -P "$BASE" && pwd)"
if [[ -e "$PREPARED_FRESH_MARKER" || -L "$PREPARED_FRESH_MARKER" ]]; then
  if [[ ! -f "$PREPARED_FRESH_MARKER" || -L "$PREPARED_FRESH_MARKER" \
    || "$(< "$PREPARED_FRESH_MARKER")" != 'prepared-local-mysql-v1' ]]; then
    echo 'Fresh database policy marker is invalid; startup refused.' >&2
    exit 1
  fi
  PREPARED_FRESH_POLICY='enabled'
fi
for prepared_tool in lsof ps; do
  if ! command -v "$prepared_tool" >/dev/null 2>&1; then
    echo "Missing $prepared_tool; cannot safely verify local database ownership." >&2
    exit 1
  fi
done

LISTEN_PID="$(lsof -nP -t -iTCP:"$MYSQL_PORT" -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "$LISTEN_PID" ]]; then
  if [[ ! "$LISTEN_PID" =~ ^[1-9][0-9]*$ || ! -f "$PID_FILE" ]]; then
    echo 'Prepared MySQL port is occupied by an unverified listener; nothing was stopped.' >&2
    exit 1
  fi
  PREPARED_IDENTITY_SQL='SELECT @@port,@@datadir'
  PREPARED_EXPECTED_IDENTITY="$MYSQL_PORT"$'\t'"$DATA/"
  if [[ -n "$PREPARED_FRESH_POLICY" ]]; then
    PREPARED_IDENTITY_SQL='SELECT @@port,@@datadir,@@partial_revokes'
    PREPARED_EXPECTED_IDENTITY+=$'\t1'
  fi
  OWN_PID="$(< "$PID_FILE")"
  PROCESS_COMMAND="$(ps -p "$LISTEN_PID" -o command= 2>/dev/null || true)"
  if [[ "$OWN_PID" != "$LISTEN_PID" || "$PROCESS_COMMAND" != "$BASE/bin/mysqld "* \
    || " $PROCESS_COMMAND " != *" --datadir=$DATA "* \
    || " $PROCESS_COMMAND " != *" --pid-file=$PID_FILE "* \
    || " $PROCESS_COMMAND " != *" --port=$MYSQL_PORT "* \
    || " $PROCESS_COMMAND " != *" --bind-address=127.0.0.1 "* ]]; then
    echo 'Prepared MySQL listener identity does not match this project; nothing was stopped or contacted.' >&2
    exit 1
  fi
  # Only authenticate AFTER process/data ownership is known. No password in argv/logs.
  if ! SERVER_IDENTITY="$(MYSQL_PWD="$PREPARED_DB_PASSWORD" "$BASE/bin/mysql" --no-defaults \
      --protocol=TCP --host=127.0.0.1 --port="$MYSQL_PORT" --connect-timeout=3 \
      --ssl-mode=REQUIRED --user="$PREPARED_DB_USERNAME" --batch --skip-column-names \
      -e "$PREPARED_IDENTITY_SQL" 2>/dev/null)" \
      || [[ "$SERVER_IDENTITY" != "$PREPARED_EXPECTED_IDENTITY" ]]; then
    echo 'Prepared MySQL authentication or canonical server identity could not be verified.' >&2
    exit 1
  fi
  echo "Independent prepared MySQL already running on 127.0.0.1:$MYSQL_PORT."
  exit 0
fi

if [[ -e "$PID_FILE" || -e "$SOCK" || -L "$SOCK" || -L "$DATA/server.log" ]]; then
  echo 'Prepared database has existing process/socket/log control paths; verify them manually before starting.' >&2
  exit 1
fi
echo "Starting independent prepared MySQL in the foreground on 127.0.0.1:$MYSQL_PORT."
exec env -i PATH="$PATH" LC_ALL=C "$BASE/bin/mysqld" --no-defaults --basedir="$BASE" --datadir="$DATA" \
  --socket="$SOCK" --pid-file="$PID_FILE" --log-error="$DATA/server.log" \
  --bind-address=127.0.0.1 --port="$MYSQL_PORT" --mysqlx=OFF ${PREPARED_FRESH_POLICY:+--partial-revokes=ON}
