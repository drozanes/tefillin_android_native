#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
HOOK_FILE="$PROJECT_ROOT/.git/hooks/pre-commit"

echo "Installing algorithm regression pre-commit hook into $HOOK_FILE..."

cat << 'EOF' > "$HOOK_FILE"
#!/usr/bin/env bash
# Git pre-commit hook for Tefillin Native algorithms regression checking

# Check if any algorithm or model asset files are staged for commit
CHANGED_ALGO_FILES=$(git diff --cached --name-only | grep -E '^(app/src/main/java/com/drozanes/tefillin/(cv|mediapipe)/|app/src/main/assets/(best_fp16\.onnx|face_landmarker\.task)|app/src/test/resources/algo_test_fixtures\.json)')

if [ -n "$CHANGED_ALGO_FILES" ]; then
    echo "================================================================================"
    echo "🔍 Algorithm or model files modified in this commit:"
    for f in $CHANGED_ALGO_FILES; do
        echo "   • $f"
    done
    echo "Running Kotlin algorithm regression tests (actual production code)..."
    echo "================================================================================"

    # Run the test suite via Gradle
    if ! ./scripts/test_algo.sh; then
        echo ""
        echo "❌ [BLOCKED] Algorithm regression tests failed!"
        echo "Commit aborted to protect algorithm integrity from unintended regression."
        echo "To run tests manually: ./scripts/test_algo.sh"
        echo "To update test fixtures from images: python3 scripts/extract_fixtures.py"
        echo "To bypass (if change is strictly intentional and verified): git commit --no-verify"
        exit 1
    fi

    echo ""
    echo "✅ [SUCCESS] All Kotlin algorithm tests passed! Proceeding with commit."
fi

exit 0
EOF

chmod +x "$HOOK_FILE"
echo "✅ Pre-commit hook installed successfully! Algorithm tests will now run automatically whenever algo files are changed."
