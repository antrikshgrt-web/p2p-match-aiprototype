#!/bin/bash
# Starts the P2P Match AI server on port 8080 (or the port given as $1).
cd "$(dirname "$0")"
if [ ! -d "out" ] || [ -z "$(ls -A out 2>/dev/null)" ]; then
  echo "Not built yet -- building first..."
  ./build.sh
fi
PORT="${1:-8080}"
echo "Starting P2P Match AI on http://localhost:$PORT"
java -cp out com.p2pmatchai.server.Server "$PORT"
