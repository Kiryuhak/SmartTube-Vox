#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${OTA_FEED_PORT:-8899}"

is_running() {
    curl -s --connect-timeout 1 "http://127.0.0.1:$PORT/health" > /dev/null 2>&1
}

echo "=== [OTA LAB] Starting / Verifying Local Feed Server ==="

if is_running; then
    echo "OTA Feed Server is already running on port $PORT."
else
    echo "Starting feed server in background on port $PORT..."
    nohup python3 "$SCRIPT_DIR/feed_server.py" > /tmp/ota_feed_server.log 2>&1 &
    disown
    for _ in $(seq 1 10); do
        if is_running; then break; fi
        sleep 0.5
    done
fi

if is_running; then
    HEALTH=$(curl -s "http://127.0.0.1:$PORT/health")
    echo "✅ Feed server healthy on http://127.0.0.1:$PORT ($HEALTH)"
    exit 0
else
    echo "❌ Feed server failed to start. Log output:"
    cat /tmp/ota_feed_server.log 2>/dev/null || true
    exit 1
fi
