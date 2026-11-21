#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python_command=python3
if ! "$python_command" -c "import sys; sys.exit(sys.version_info.major != 3)" >/dev/null 2>&1; then
  python_command=python
fi

case "${1:-}" in
  nominal|token)
    exec "$python_command" scripts/demo.py "$1"
    ;;
  compensation)
    base=(docker compose -f docker-compose.yml)
    restore() {
      local status=$?
      trap - EXIT
      "${base[@]}" up -d --no-deps --wait --wait-timeout 150 autopartage reservation || status=1
      exit "$status"
    }
    trap restore EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    "${base[@]}" -f infra/demo/compose-lot9.yml up -d --no-deps --wait --wait-timeout 150 autopartage reservation
    "$python_command" scripts/demo.py compensation
    ;;
  *)
    echo 'Usage: bash scripts/demo.sh nominal|compensation|token' >&2
    exit 2
    ;;
esac
