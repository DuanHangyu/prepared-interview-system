#!/usr/bin/env bash
# Explicit fresh-server step. Never resets or adopts an existing database.
set -euo pipefail
umask 077
ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.local}"
if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" ]]; then
  echo 'A regular owner-only prepared configuration is required.' >&2
  exit 1
fi
# Check before sourcing, not after executing an insecure configuration file.
if [[ "$(uname -s)" == 'Darwin' ]]; then
  prepared_mode="$(stat -f '%Lp' "$ENV_FILE")"
  prepared_owner="$(stat -f '%u' "$ENV_FILE")"
else
  prepared_mode="$(stat -c '%a' "$ENV_FILE")"
  prepared_owner="$(stat -c '%u' "$ENV_FILE")"
fi
if [[ "$prepared_mode" != '600' || "$prepared_owner" != "$(id -u)" ]]; then
  echo 'Configuration must have mode 600 and belong to the current user.' >&2
  exit 1
fi
# shellcheck source=prepared-local-config.sh
source "$ROOT_DIR/scripts/prepared-local-config.sh"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  prepared_java="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  prepared_java="$(command -v java)"
else
  echo 'Java 21 is required for the independent database initializer.' >&2
  exit 1
fi
# The helper and its own daemons receive no legacy/admin/model credentials.
# Generated administrator and migration passwords never enter the shell env.
exec env -i PATH='/usr/bin:/bin:/usr/sbin:/sbin' LC_ALL=C \
  ENV_FILE="$ENV_FILE" SPRING_PROFILE='prepared-local' \
  PREPARED_MYSQL_BASEDIR="${PREPARED_MYSQL_BASEDIR:-}" \
  PREPARED_MYSQL_PORT="${PREPARED_MYSQL_PORT:-13317}" \
  PREPARED_DB_JDBC_URL="${PREPARED_DB_JDBC_URL:-}" \
  PREPARED_DB_USERNAME="${PREPARED_DB_USERNAME:-}" \
  PREPARED_DB_PASSWORD="${PREPARED_DB_PASSWORD:-}" \
  PREPARED_BACKEND_PORT="$PREPARED_BACKEND_PORT" PREPARED_FRONTEND_PORT="$PREPARED_FRONTEND_PORT" \
  "$prepared_java" "$ROOT_DIR/defense-assessment/src/main/java/system/assessment/defense/prepared/bootstrap/PreparedLocalMysqlBootstrap.java" "$ROOT_DIR"
