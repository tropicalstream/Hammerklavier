#!/usr/bin/env bash
# The CI gate (PLAN §7.1 rule 6), run before every merge:
#   check_purity.sh → gradle :core:test :app:testDebugUnitTest :app:assembleRelease (through tools/gw,
#   which waits for one of the two shared gradle slots on this Mac) → pipeline unit tests →
#   check_ledger.py → size_report.py. Pipeline steps not delivered yet by WP11 are reported as SKIP.
#
#   tools/ci.sh               the gate for this worktree
#   tools/ci.sh --contracts   (WP0, after every contracts-changelog entry) compile every open wp*
#                             branch with this checkout's contract/** laid over it; report breakage
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"; cd "$ROOT"

step() { printf '\n[ci] %s\n' "$*"; }

if [ "${1:-}" = "--contracts" ]; then
    CONTRACT_DIRS="core/src/main/java/com/tropicalstream/hammerklavier/contract app/src/main/java/com/tropicalstream/hammerklavier/contract"
    SRC_REV=$(git rev-parse HEAD)
    broken=0; checked=0
    for br in $(git for-each-ref --format='%(refname:short)' 'refs/heads/wp*'); do
        tmp=$(mktemp -d /tmp/hk-contracts.XXXXXX)
        rmdir "$tmp"
        git worktree add --detach --quiet "$tmp" "$br"
        printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$tmp/local.properties"
        # shellcheck disable=SC2086
        git -C "$tmp" checkout --quiet "$SRC_REV" -- $CONTRACT_DIRS
        step "contracts over $br"
        if (cd "$tmp" && "$ROOT/tools/gw" --quiet :core:compileTestKotlin :app:compileDebugUnitTestKotlin); then
            echo "[ci] $br: OK"
        else
            echo "[ci] $br: BROKEN by the contracts at $SRC_REV"; broken=$((broken + 1))
        fi
        git worktree remove --force "$tmp"
        checked=$((checked + 1))
    done
    echo "[ci] --contracts: $checked branches checked, $broken broken"
    [ "$broken" -eq 0 ]
    exit
fi

step "purity"
"$ROOT/tools/check_purity.sh"

step "gradle :core:test :app:testDebugUnitTest :app:assembleRelease"
"$ROOT/tools/gw" :core:test :app:testDebugUnitTest :app:assembleRelease

step "nio linkage (ART-safe java.nio calls in :core)"
"$ROOT/tools/check_nio_linkage.sh"

step "pipeline unit tests"
if [ -d tools/pipeline/tests ]; then python3 -m unittest discover tools/pipeline/tests; else echo "[ci] SKIP tools/pipeline/tests (not delivered yet)"; fi

step "ledger"
if [ -f tools/pipeline/check_ledger.py ]; then python3 tools/pipeline/check_ledger.py; else echo "[ci] SKIP tools/pipeline/check_ledger.py (not delivered yet)"; fi

step "size report"
if [ -f tools/pipeline/size_report.py ]; then python3 tools/pipeline/size_report.py; else echo "[ci] SKIP tools/pipeline/size_report.py (not delivered yet)"; fi

printf '\n[ci] PASS %s (%s)\n' "$(git rev-parse --short=12 HEAD 2>/dev/null || echo uncommitted)" "$(git symbolic-ref --short -q HEAD || echo detached)"
