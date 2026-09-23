#!/usr/bin/env bash
# :core compiles against the JDK 17 API, where java.nio buffers have covariant overrides
# (ByteBuffer.position(I)Ljava/nio/ByteBuffer;, MappedByteBuffer.duplicate(), …) that ART on the
# glasses lacks (NoSuchMethodError at run time, M1). Fails if any :core class links one; the fix is
# to call through java.nio.Buffer / ByteBuffer: `(b as Buffer).position(n)`, `(map as ByteBuffer).duplicate()`.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
DIR=core/build/classes/kotlin/main
[ -d "$DIR" ] || { echo "check_nio_linkage: no $DIR (build :core first)" >&2; exit 1; }
bad=$(find "$DIR" -name '*.class' -print0 | xargs -0 javap -c -p 2>/dev/null | grep -E 'Method java/nio/[A-Za-z]*Buffer\.(position|limit|flip|clear|mark|reset|rewind):\(I?\)Ljava/nio/[A-Z][A-Za-z]*Buffer;|Method java/nio/MappedByteBuffer\.(duplicate|slice)' | sort -u || true)
if [ -n "$bad" ]; then echo "check_nio_linkage: covariant java.nio calls ART lacks:"; echo "$bad"; exit 1; fi
echo "check_nio_linkage: OK"
