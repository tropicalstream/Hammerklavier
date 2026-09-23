# Sourced by every tools/* script (PLAN §7.1 rule 3):  ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
# ANDROID_HOME is not set in this Mac's shell; local.properties (git-ignored) names the SDK per worktree.
# Never put an absolute project path here: every script works on the worktree it is run from.

ROOT="${ROOT:-$(git rev-parse --show-toplevel)}"
export ROOT

if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -f "$ROOT/local.properties" ] && grep -q '^sdk.dir=' "$ROOT/local.properties"; then
        ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | head -n 1)"
    else
        ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    fi
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
case ":$PATH:" in *":$ANDROID_HOME/platform-tools:"*) ;; *) export PATH="$ANDROID_HOME/platform-tools:$PATH" ;; esac

export HK_SERIAL="${HK_SERIAL:-A06B4A96A733283}"      # the X3 Pro; always pass -s
export HK_PKG=com.tropicalstream.hammerklavier
export HK_DEVICE_LOCK="${HK_DEVICE_LOCK:-/tmp/hk-device.lock}"
