#!/usr/bin/env bash
# Explicit local migrations using only the dedicated least-privilege identity.
set -euo pipefail
umask 077
ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.local}"
if [[ ! -f "$ENV_FILE" || -L "$ENV_FILE" ]]; then
  echo 'A regular owner-only prepared configuration is required.' >&2
  exit 1
fi
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
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo 'Set JAVA_HOME to a Java 21 JDK for the independent migration command.' >&2
  exit 1
fi
if [[ ! -d "$ROOT_DIR/.runtime" || -L "$ROOT_DIR/.runtime" \
  || ! -f "$ROOT_DIR/.runtime/mysql-bootstrap.complete" || -L "$ROOT_DIR/.runtime/mysql-bootstrap.complete" \
  || "$(< "$ROOT_DIR/.runtime/mysql-bootstrap.complete")" != 'prepared-local-mysql-v1' ]]; then
  echo 'Independent completed initialization is required; existing databases are not adopted.' >&2
  exit 1
fi
if [[ "$(uname -s)" == 'Darwin' ]]; then
  prepared_runtime_mode="$(stat -f '%Lp' "$ROOT_DIR/.runtime")"
  prepared_runtime_owner="$(stat -f '%u' "$ROOT_DIR/.runtime")"
else
  prepared_runtime_mode="$(stat -c '%a' "$ROOT_DIR/.runtime")"
  prepared_runtime_owner="$(stat -c '%u' "$ROOT_DIR/.runtime")"
fi
if [[ "$prepared_runtime_mode" != '700' || "$prepared_runtime_owner" != "$(id -u)" ]]; then
  echo 'Dedicated runtime directory must be owner-only.' >&2
  exit 1
fi
prepared_build_dir="$(mktemp -d "$ROOT_DIR/.runtime/mysql-migration-build.XXXXXXXX")"
prepared_maven_cache="${HOME:?Original user home is required for the Maven cache}/.m2"
cd "$ROOT_DIR/defense-assessment"
# Build receives no app/model/admin credentials. Preserve only the normal cache
# path, never change HOME or global Maven/user configuration.
if ! env -i PATH="$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin" JAVA_HOME="$JAVA_HOME" LC_ALL=C \
  MAVEN_USER_HOME="$prepared_maven_cache" ./mvnw -q -DskipTests compile dependency:build-classpath \
  -Dmdep.includeScope=runtime -Dmdep.outputFile="$prepared_build_dir/classpath.txt" \
  > "$prepared_build_dir/build.log" 2>&1; then
  echo "Migration build failed; private diagnostics: $prepared_build_dir/build.log" >&2
  exit 1
fi
if [[ ! -f "$prepared_build_dir/classpath.txt" || -L "$prepared_build_dir/classpath.txt" ]]; then
  echo 'Migration runtime classpath was not produced.' >&2
  exit 1
fi
prepared_classpath="$ROOT_DIR/defense-assessment/target/classes:$(< "$prepared_build_dir/classpath.txt")"
# The Java command reads the private migration option file only after proving
# the exact local server/process/socket namespace. No password appears in argv.
if ! env -i PATH="$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin" JAVA_HOME="$JAVA_HOME" LC_ALL=C \
  ENV_FILE="$ENV_FILE" SPRING_PROFILE='prepared-local' \
  PREPARED_MYSQL_BASEDIR="${PREPARED_MYSQL_BASEDIR:-}" PREPARED_MYSQL_PORT="${PREPARED_MYSQL_PORT:-13317}" \
  PREPARED_DB_JDBC_URL="${PREPARED_DB_JDBC_URL:-}" PREPARED_DB_USERNAME="${PREPARED_DB_USERNAME:-}" \
  PREPARED_DB_PASSWORD="${PREPARED_DB_PASSWORD:-}" \
  PREPARED_BACKEND_PORT="$PREPARED_BACKEND_PORT" PREPARED_FRONTEND_PORT="$PREPARED_FRONTEND_PORT" \
  "$JAVA_HOME/bin/java" -cp "$prepared_classpath" system.assessment.defense.prepared.bootstrap.PreparedLocalMysqlMigration "$ROOT_DIR" \
  > "$prepared_build_dir/migration.log" 2>&1; then
  echo "Migration refused or failed; private diagnostics: $prepared_build_dir/migration.log" >&2
  exit 1
fi
echo 'Independent migrations completed; runtime application credentials remain separate.'
