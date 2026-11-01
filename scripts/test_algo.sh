#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "================================================================================"
echo "▶ RUNNING NATIVE UNIT & REGRESSION TEST SUITE (29 TESTS)"
echo "================================================================================"

./gradlew testDebugUnitTest "$@"

echo "================================================================================"
echo "🎉 ALL TESTS PASSED SUCCESSFULLY!"
echo "================================================================================"
