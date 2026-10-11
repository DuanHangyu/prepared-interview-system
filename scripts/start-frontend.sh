#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepared-local-config.sh
source "$ROOT_DIR/scripts/prepared-local-config.sh"

# Local launches always target their own prepared backend, never an inherited old proxy.
export VUE_APP_PROXY_TARGET="http://127.0.0.1:$PREPARED_BACKEND_PORT/"

cd "$ROOT_DIR/defense-system"
exec npm run serve -- --host 127.0.0.1 --port "$PREPARED_FRONTEND_PORT" --strictPort
