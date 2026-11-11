#!/bin/sh
# Candidate only. Keep the operation journal on the VPS.
set -eu
exec python3 -B "$(dirname "$0")/ports-temporary.py" "$@"
