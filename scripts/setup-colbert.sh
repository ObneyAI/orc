#!/bin/bash
# Setup ColBERT Python environment for semantic search.
#
# Creates a dedicated Python virtual environment with RAGatouille and
# ColBERT dependencies for neural late-interaction retrieval, installed
# from the pinned, verified-working requirements set in
# development/bench/requirements.txt.
#
# Usage:
#   ./scripts/setup-colbert.sh           # creates or reuses .venv-colbert
#   ./scripts/setup-colbert.sh --force   # always recreate (no prompt)
#
# After setup, smoke-test the bridge:
#   echo '{"id":1,"method":"ping","params":{}}' | .venv-colbert/bin/python scripts/colbert_bridge.py
#   # → {"id": 1, "result": {"status": "ok"}}
#
# Or from Clojure REPL:
#   (require '[ai.obney.orc.colbert.interface :as colbert])
#   (colbert/ping)
#
# For a deeper check (bridge + tiny index build):
#   clj -M:dev -e '(require (quote [ai.obney.orc.colbert.interface :as colbert])) (colbert/health-check)'

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
VENV_DIR="$PROJECT_ROOT/.venv-colbert"
REQUIREMENTS_FILE="$PROJECT_ROOT/development/bench/requirements.txt"

echo "=== ColBERT Environment Setup ==="
echo ""

# Sanity-check: requirements.txt must exist
if [ ! -f "$REQUIREMENTS_FILE" ]; then
    echo "Error: $REQUIREMENTS_FILE not found"
    echo ""
    echo "This script installs from a pinned requirements set. If you're"
    echo "on a branch that hasn't landed the pinned file yet, regenerate"
    echo "from a working venv via:"
    echo "  .venv-colbert/bin/pip freeze > development/bench/requirements.txt"
    exit 1
fi

# Check Python is available
if ! command -v python3 &> /dev/null; then
    echo "Error: python3 is required but not found"
    exit 1
fi

PYTHON_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
echo "Found Python $PYTHON_VERSION"

# Verified-working on Python 3.12. 3.11 also expected to work (ragatouille
# supports 3.10-3.12). 3.13 has not been verified — torch may not have
# wheels for it yet.
case "$PYTHON_VERSION" in
    3.10|3.11|3.12)
        ;;
    *)
        echo "WARNING: Python $PYTHON_VERSION has not been verified. Known-working: 3.10, 3.11, 3.12."
        ;;
esac

# Force-recreate when --force flag passed; otherwise prompt if venv exists.
FORCE_RECREATE=false
if [ "$1" = "--force" ]; then
    FORCE_RECREATE=true
fi

if [ -d "$VENV_DIR" ]; then
    # Check the venv's python actually works (the symlink may be broken
    # if the system python the venv was built against has been removed).
    if [ ! -x "$VENV_DIR/bin/python" ] || ! "$VENV_DIR/bin/python" --version &>/dev/null; then
        echo "Existing venv is broken (python interpreter missing or unrunnable)."
        echo "Recreating..."
        rm -rf "$VENV_DIR"
    elif $FORCE_RECREATE; then
        echo "Recreating venv (--force)..."
        rm -rf "$VENV_DIR"
    else
        echo "Virtual environment exists at $VENV_DIR"
        read -p "Recreate? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm -rf "$VENV_DIR"
        fi
    fi
fi

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment..."
    python3 -m venv "$VENV_DIR"
fi

# Install pinned dependencies. The requirements file is a full pip freeze
# from a verified-working venv — see its top-of-file comment for why every
# transitive dep is pinned.
echo "Upgrading pip..."
"$VENV_DIR/bin/pip" install --upgrade pip >/dev/null

echo "Installing pinned dependencies from $REQUIREMENTS_FILE..."
"$VENV_DIR/bin/pip" install -r "$REQUIREMENTS_FILE"

# Smoke-test so we don't ship a venv that fails at the first real call.
# IMPORTANT: the bridge `ping` does NOT import ragatouille, so a ping alone can
# pass against a venv where the install half-failed (e.g. a dependency-resolution
# conflict left ragatouille uninstalled). Check the real import FIRST.
echo ""
echo "Smoke-testing: import ragatouille..."
if ! "$VENV_DIR/bin/python" -c "import ragatouille" 2>/tmp/colbert-smoke-import.err; then
    echo "  ✗ 'import ragatouille' FAILED — the venv is broken:"
    sed 's/^/    /' /tmp/colbert-smoke-import.err | tail -n 8
    echo ""
    echo "A fresh 'pip install -r' may have hit a dependency-resolution conflict"
    echo "and installed nothing. See development/bench/SETUP.md for troubleshooting."
    exit 1
fi
echo "  ✓ ragatouille imports"

echo "Smoke-testing the bridge ping..."
SMOKE_RESULT=$(echo '{"id":1,"method":"ping","params":{}}' | \
               "$VENV_DIR/bin/python" "$PROJECT_ROOT/scripts/colbert_bridge.py" 2>&1 | \
               tail -n 1)
if echo "$SMOKE_RESULT" | grep -q '"status": "ok"'; then
    echo "  ✓ Bridge ping returned ok"
else
    echo "  ✗ Bridge ping failed:"
    echo "    $SMOKE_RESULT"
    echo ""
    echo "See development/bench/SETUP.md for troubleshooting."
    exit 1
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "ColBERT environment ready at: $VENV_DIR"
echo ""
echo "Deeper health-check (bridge + tiny index build):"
echo "  clj -M:dev -e '(require (quote [ai.obney.orc.colbert.interface :as colbert])) (colbert/health-check)'"
