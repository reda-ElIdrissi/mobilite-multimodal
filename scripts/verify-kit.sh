#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root/.runtime"
for attempt in {1..15}; do
    if bash "$root/starter-kit-mobilite/verify.sh"; then
        exit 0
    fi
    sleep 2
done
exit 1
