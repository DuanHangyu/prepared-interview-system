#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepared-local-config.sh
source "$ROOT_DIR/scripts/prepared-local-config.sh"

# A port number is not process ownership. Never signal an unrelated/old listener.
require_free_port() {
  local port="$1"
  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "Prepared port $port is occupied; stop the identified prepared process explicitly before relaunching. No listener was stopped."
    exit 1
  fi
}

if ! command -v lsof >/dev/null 2>&1; then
  echo 'lsof is required to check local port ownership safely; no service was started.'
  exit 1
fi
require_free_port "$PREPARED_BACKEND_PORT"
require_free_port "$PREPARED_FRONTEND_PORT"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

nohup "$ROOT_DIR/scripts/start-backend.sh" > "$RUN_DIR/backend.log" 2>&1 &
BACKEND_PID="$!"
echo "$BACKEND_PID" > "$RUN_DIR/backend.pid"

nohup "$ROOT_DIR/scripts/start-frontend.sh" > "$RUN_DIR/frontend.log" 2>&1 &
FRONTEND_PID="$!"
echo "$FRONTEND_PID" > "$RUN_DIR/frontend.pid"

echo "Backend starting on http://127.0.0.1:$PREPARED_BACKEND_PORT (pid $BACKEND_PID, log .run/backend.log)"
echo "Frontend starting on http://127.0.0.1:$PREPARED_FRONTEND_PORT (pid $FRONTEND_PID, log .run/frontend.log)"
