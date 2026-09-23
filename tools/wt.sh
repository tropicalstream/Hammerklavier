#!/usr/bin/env bash
# Create a WP worktree (PLAN §7.1 rule 3):  tools/wt.sh <N> <slug> [base]
#   git worktree add ../hk-wp<N> -b wp<N>-<slug> <base, default contracts-v1>
# then writes local.properties (git-ignored; the SDK path) and the tropicalstream identity.
# Copies nothing else.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"

if [ $# -lt 2 ]; then echo "usage: tools/wt.sh <N> <slug> [base]" >&2; exit 2; fi
N="$1"; SLUG="$2"; BASE="${3:-contracts-v1}"
case "$N" in ''|*[!0-9]*) echo "wt.sh: N must be a number" >&2; exit 2 ;; esac
DIR="$(cd "$ROOT/.." && pwd)/hk-wp$N"
BRANCH="wp$N-$SLUG"

if [ -e "$DIR" ]; then echo "wt.sh: $DIR already exists" >&2; exit 1; fi
git -C "$ROOT" rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || { echo "wt.sh: no such base '$BASE'" >&2; exit 1; }

git -C "$ROOT" worktree add "$DIR" -b "$BRANCH" "$BASE"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$DIR/local.properties"
git -C "$DIR" config user.name tropicalstream
git -C "$DIR" config user.email tropicalstream@users.noreply.github.com

echo "worktree $DIR on branch $BRANCH (from $BASE)"
echo "identity: $(git -C "$DIR" config user.name) <$(git -C "$DIR" config user.email)>"
