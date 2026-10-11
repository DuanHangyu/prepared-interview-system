#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepared-local-config.sh
source "$ROOT_DIR/scripts/prepared-local-config.sh"

for prepared_required in PREPARED_DB_JDBC_URL PREPARED_DB_USERNAME PREPARED_DB_PASSWORD; do
  if [[ -z "${!prepared_required:-}" ]]; then
    echo "Missing $prepared_required; legacy database variables are not used."
    exit 1
  fi
done
# Reject old schemas before even opening a tunnel or probing a database.
if [[ ! "$PREPARED_DB_JDBC_URL" =~ ^jdbc:mysql://[a-zA-Z0-9.-]+(:[0-9]+)?/prepared_interview(_[a-z0-9]+)*(\?.*)?$ ]]; then
  echo 'PREPARED_DB_JDBC_URL must identify an isolated prepared_interview database.'
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
elif [[ -d "/Users/duanhangyu/.homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/Users/duanhangyu/.homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

"$ROOT_DIR/scripts/start-db-tunnel.sh"

DB_HOST_PORT="${PREPARED_DB_JDBC_URL#jdbc:mysql://}"
DB_HOST_PORT="${DB_HOST_PORT%%/*}"
DB_HOST="${DB_HOST_PORT%:*}"
DB_PORT="${DB_HOST_PORT##*:}"
if [[ "$DB_HOST" == "$DB_PORT" ]]; then
  DB_PORT="3306"
fi

for attempt in {1..20}; do
  if nc -z -w 2 "$DB_HOST" "$DB_PORT" >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == "20" ]]; then
    echo 'Prepared database is not reachable.'
    echo 'Check PREPARED_DB_JDBC_URL and the independent tunnel settings.'
    exit 1
  fi
  sleep 0.5
done

cd "$ROOT_DIR/defense-assessment"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=prepared-local
