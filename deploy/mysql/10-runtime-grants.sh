#!/bin/bash
set -Eeuo pipefail

case "${MYSQL_USER:?MYSQL_USER is required}" in
  *[!a-zA-Z0-9_]*)
    echo "MYSQL_USER may contain only letters, digits, and underscores" >&2
    exit 1
    ;;
esac

case "${MYSQL_DATABASE:?MYSQL_DATABASE is required}" in
  *[!a-zA-Z0-9_]*)
    echo "MYSQL_DATABASE may contain only letters, digits, and underscores" >&2
    exit 1
    ;;
esac

apply_runtime_grants() {
  if declare -F docker_process_sql >/dev/null; then
    docker_process_sql
  else
    MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}" \
      mysql --protocol=socket -uroot
  fi
}

apply_runtime_grants <<EOSQL
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${MYSQL_USER}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
