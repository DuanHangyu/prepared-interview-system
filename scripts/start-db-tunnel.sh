#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/start-db-tunnel.sh"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.local}"
RUN_DIR="$ROOT_DIR/.run"
LOG_FILE="$RUN_DIR/db-tunnel.log"
WATCH_PID_FILE="$RUN_DIR/db-tunnel-watch.pid"
mkdir -p "$RUN_DIR"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

if [[ "${DB_TUNNEL_ENABLED:-false}" != "true" ]]; then
  exit 0
fi

WATCH_MODE="false"
if [[ "${1:-}" == "--watch" ]]; then
  WATCH_MODE="true"
fi

LOCAL_HOST="${DB_TUNNEL_LOCAL_HOST:-127.0.0.1}"
LOCAL_PORT="${DB_TUNNEL_LOCAL_PORT:-13307}"
REMOTE_HOST="${DB_TUNNEL_REMOTE_HOST:-127.0.0.1}"
REMOTE_PORT="${DB_TUNNEL_REMOTE_PORT:-3306}"
SSH_HOST="${DB_TUNNEL_SSH_HOST:-}"
SSH_USER="${DB_TUNNEL_SSH_USER:-root}"
SSH_KEY="${DB_TUNNEL_SSH_KEY:-}"
CHECK_INTERVAL="${DB_TUNNEL_CHECK_INTERVAL:-5}"

is_local_port_open() {
  nc -z -w 2 "$LOCAL_HOST" "$LOCAL_PORT" >/dev/null 2>&1
}

wait_for_local_port() {
  local attempt
  for attempt in {1..20}; do
    if is_local_port_open; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

start_tunnel_once() {
  if is_local_port_open; then
    return 0
  fi

  if [[ -z "$SSH_HOST" ]]; then
    echo "DB_TUNNEL_ENABLED=true but DB_TUNNEL_SSH_HOST is empty"
    exit 1
  fi

  if [[ -n "$SSH_KEY" ]]; then
    ssh \
      -fN \
      -i "$SSH_KEY" \
      -o IdentitiesOnly=yes \
      -o PreferredAuthentications=publickey \
      -o PasswordAuthentication=no \
      -o StrictHostKeyChecking=accept-new \
      -o ServerAliveInterval=30 \
      -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes \
      -L "$LOCAL_HOST:$LOCAL_PORT:$REMOTE_HOST:$REMOTE_PORT" \
      "$SSH_USER@$SSH_HOST"
  elif [[ -n "${DB_TUNNEL_SSH_PASSWORD:-}" ]]; then
    if ! command -v sshpass >/dev/null 2>&1; then
      echo "sshpass is required when DB_TUNNEL_SSH_PASSWORD is set"
      exit 1
    fi
    SSHPASS="$DB_TUNNEL_SSH_PASSWORD" sshpass -e ssh \
      -fN \
      -o PreferredAuthentications=password \
      -o PubkeyAuthentication=no \
      -o NumberOfPasswordPrompts=1 \
      -o StrictHostKeyChecking=accept-new \
      -o ServerAliveInterval=30 \
      -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes \
      -L "$LOCAL_HOST:$LOCAL_PORT:$REMOTE_HOST:$REMOTE_PORT" \
      "$SSH_USER@$SSH_HOST"
  else
    ssh \
      -fN \
      -o StrictHostKeyChecking=accept-new \
      -o ServerAliveInterval=30 \
      -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes \
      -L "$LOCAL_HOST:$LOCAL_PORT:$REMOTE_HOST:$REMOTE_PORT" \
      "$SSH_USER@$SSH_HOST"
  fi

  if ! wait_for_local_port; then
    echo "DB tunnel failed to listen on $LOCAL_HOST:$LOCAL_PORT"
    exit 1
  fi
}

watcher_is_running() {
  [[ -f "$WATCH_PID_FILE" ]] && kill -0 "$(cat "$WATCH_PID_FILE")" >/dev/null 2>&1
}

start_watcher() {
  if [[ "${DB_TUNNEL_WATCH:-true}" != "true" ]]; then
    return 0
  fi
  if watcher_is_running; then
    return 0
  fi
  DB_TUNNEL_WATCH_CHILD=true nohup "$SCRIPT_PATH" --watch >> "$LOG_FILE" 2>&1 &
  echo $! > "$WATCH_PID_FILE"
}

if [[ "$WATCH_MODE" == "true" ]]; then
  echo "DB tunnel watcher started for $LOCAL_HOST:$LOCAL_PORT"
  while true; do
    if ! is_local_port_open; then
      echo "$(date '+%Y-%m-%d %H:%M:%S') DB tunnel down, reconnecting..."
      start_tunnel_once || true
    fi
    sleep "$CHECK_INTERVAL"
  done
fi

start_tunnel_once
start_watcher
