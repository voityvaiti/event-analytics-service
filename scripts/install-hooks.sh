#!/bin/bash

# Opt-in installer for the project's git hooks. Run once after cloning:
#
#   ./scripts/install-hooks.sh
#
# Symlinks scripts/pre-commit into .git/hooks so future edits to the hook
# script take effect without re-running this. Hooks are intentionally NOT
# installed automatically — CI runs the same checks as the safety net.

set -e

ROOT="$(git rev-parse --show-toplevel)"
HOOK_DIR="$ROOT/.git/hooks"

mkdir -p "$HOOK_DIR"
chmod +x "$ROOT/scripts/pre-commit"
ln -sf ../../scripts/pre-commit "$HOOK_DIR/pre-commit"

echo "Installed: .git/hooks/pre-commit -> scripts/pre-commit"
