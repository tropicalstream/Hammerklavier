#!/usr/bin/env bash
# Purity gate (PLAN §2.2): the :core module is JVM-only, so the compiler already rejects android.*;
# this also catches fully qualified android./androidx./java.awt/javax. names and stray imports in
# every directory listed in tools/purity_dirs.txt. Comment lines are ignored.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"; cd "$ROOT"

fail=0
while IFS= read -r dir; do
    case "$dir" in ''|'#'*) continue ;; esac
    [ -d "$dir" ] || { echo "check_purity: $dir missing" >&2; fail=1; continue; }
    # Imports outside the allowlist.
    bad_imports=$(grep -rnE --include='*.kt' --include='*.java' '^[[:space:]]*import[[:space:]]' "$dir" |
        grep -vE ':[[:space:]]*import[[:space:]]+(kotlin|java|org\.json|com\.tropicalstream\.hammerklavier)\.' || true)
    awt_imports=$(grep -rnE --include='*.kt' --include='*.java' '^[[:space:]]*import[[:space:]]+java\.awt' "$dir" || true)
    # Fully qualified platform names in code (not in comment lines).
    fq=$(grep -rnE --include='*.kt' --include='*.java' '(^|[^A-Za-z0-9_.])(android|androidx|javax)\.[a-z]|java\.awt' "$dir" |
        grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' || true)
    for v in "$bad_imports" "$awt_imports" "$fq"; do
        if [ -n "$v" ]; then echo "$v"; fail=1; fi
    done
done < "$ROOT/tools/purity_dirs.txt"

if [ "$fail" -ne 0 ]; then echo "check_purity: FAIL (pure code may use only kotlin.*, java.* without java.awt, org.json.*)" >&2; exit 1; fi
echo "check_purity: OK"
