#!/usr/bin/env bash
# Serialise the one pair of glasses (PLAN §7.1 rule 4):  tools/device/lock.sh -- <command> [args…]
# Waits for an exclusive fcntl.flock on $HK_DEVICE_LOCK (/tmp/hk-device.lock; the Mac has no
# flock(1)), runs the command, and before releasing the lock restores `device_wearing 0` on the
# glasses (bench scripts set it to 1), also when the command fails or is interrupted.
# Re-entrant via HK_DEVICE_LOCK_HELD (set for the child), so wrapping smoke.sh in lock.sh is safe.
# HK_LOCK_NO_ADB=1 skips the restore (for testing the lock without a device).
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"

[ "${1:-}" = "--" ] && shift
if [ $# -eq 0 ]; then echo "usage: tools/device/lock.sh -- <command> [args…]" >&2; exit 2; fi
# Re-entrant: a command already running under this lock (smoke.sh, run.sh inside an outer lock.sh) runs directly.
if [ "${HK_DEVICE_LOCK_HELD:-}" = "$HK_DEVICE_LOCK" ]; then exec "$@"; fi

# The locker runs from -c (not a heredoc on stdin), so the command keeps this script's stdin.
LOCKER=$(cat <<'PY'
import fcntl, os, signal, subprocess, sys, time

lock_path, serial, cmd = sys.argv[1], sys.argv[2], sys.argv[3:]   # argv[0] is '-c'
f = open(lock_path, "a+")
t0 = time.time()
announced = False
while True:
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if not announced:
            print("[lock] waiting for the device lock %s" % lock_path, file=sys.stderr, flush=True)
            announced = True
        time.sleep(1)
if announced:
    print("[lock] got the device lock after %.0f s" % (time.time() - t0), file=sys.stderr, flush=True)
f.seek(0); f.truncate(); f.write("%d %s\n" % (os.getpid(), " ".join(cmd))); f.flush()

child = None
def forward(sig, _frame):
    if child is not None and child.poll() is None:
        child.send_signal(sig)
    else:
        raise KeyboardInterrupt
for s in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
    signal.signal(s, forward)

code = 1
try:
    child = subprocess.Popen(cmd, env=dict(os.environ, HK_DEVICE_LOCK_HELD=lock_path))
    code = child.wait()
except KeyboardInterrupt:
    code = 130
finally:
    if os.environ.get("HK_LOCK_NO_ADB") != "1":
        try:
            subprocess.run(["adb", "-s", serial, "shell", "settings", "put", "global", "device_wearing", "0"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        except Exception:
            pass
    f.seek(0); f.truncate(); f.flush()
    fcntl.flock(f, fcntl.LOCK_UN)
    f.close()
sys.exit(code)
PY
)
exec python3 -c "$LOCKER" "$HK_DEVICE_LOCK" "$HK_SERIAL" "$@"
