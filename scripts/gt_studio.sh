#!/usr/bin/env bash
#
# Launches the Tefillin Ground Truth & Algorithm Studio Web Application.
# Opens automatically in the default browser at http://127.0.0.1:8080.
#

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"
python3 "$SCRIPT_DIR/gt_studio.py" "$@"
