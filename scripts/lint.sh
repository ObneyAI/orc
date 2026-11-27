#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

if ! command -v bb >/dev/null 2>&1; then
    echo "Lint checks require Babashka (bb) on PATH." >&2
    exit 1
fi

bb scripts/lint_code_comments.bb
bb scripts/lint_explicit_dependencies.bb
bb scripts/lint_no_declare.bb
bb scripts/lint_no_sql_delete.bb
bb scripts/lint_service_components.bb
bb scripts/lint_grain_declarations.bb
bb scripts/lint_view_files.bb
bb scripts/lint_logic_files.bb
bb scripts/lint_grain_logic.bb
bb scripts/lint_read_model_versions.bb
bb scripts/lint_no_any_schemas.bb
