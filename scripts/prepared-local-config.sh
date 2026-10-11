#!/usr/bin/env bash
# Shared local-only launch boundary. Source from a launcher after resolving ROOT_DIR.
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.local}"
if [[ ! -f "$ENV_FILE" || ! -r "$ENV_FILE" ]]; then
  echo 'Missing readable prepared local configuration; create .env.local inside the prepared project.'
  exit 1
fi
if [[ -L "$ENV_FILE" ]]; then
  echo 'Configuration must be a regular file inside the prepared project, not a symbolic link.'
  exit 1
fi
prepared_env_dir="$(cd -P -- "$(dirname "$ENV_FILE")" && pwd)"
case "$prepared_env_dir/" in
  "$ROOT_DIR/"*) ;;
  *) echo 'Configuration must be inside the prepared project; legacy/external env files are rejected.'; exit 1 ;;
esac

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

if [[ "${SPRING_PROFILE:-prepared-local}" != 'prepared-local' ]]; then
  echo 'Local launch requires SPRING_PROFILE=prepared-local; legacy profiles are rejected.'
  exit 1
fi
export SPRING_PROFILE='prepared-local'
export PREPARED_BACKEND_PORT="${PREPARED_BACKEND_PORT:-13014}"
export PREPARED_FRONTEND_PORT="${PREPARED_FRONTEND_PORT:-13015}"

prepared_validate_port() {
  local setting="$1" value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]{3,4}$ ]] || (( 10#$value < 1024 || 10#$value > 65535 )); then
    echo "Invalid $setting; expected an unprivileged TCP port."
    exit 1
  fi
}
prepared_validate_port PREPARED_BACKEND_PORT "$PREPARED_BACKEND_PORT"
prepared_validate_port PREPARED_FRONTEND_PORT "$PREPARED_FRONTEND_PORT"
if [[ "$PREPARED_BACKEND_PORT" == "$PREPARED_FRONTEND_PORT" ]]; then
  echo 'Prepared backend and frontend must use different ports.'
  exit 1
fi
