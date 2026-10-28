#!/bin/sh
# Build adbstitch as an Android native "library" (fake .so) so it can be
# packaged in jniLibs/ and executed from nativeLibraryDir — app-private
# data dirs are mounted noexec on modern Android.
#
# Requires the Android NDK. Set NDK_HOME or let it auto-detect under $ANDROID_HOME/ndk.
set -e

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$SRC_DIR/../app/src/main/jniLibs/arm64-v8a"

NDK="${NDK_HOME:-$ANDROID_HOME/ndk}"
if [ ! -d "$NDK" ]; then
    NDK=$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "NDK not found: set NDK_HOME or ANDROID_HOME" >&2
    exit 1
fi

API=34
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
if [ ! -x "$CLANG" ]; then
    CLANG=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android*-clang 2>/dev/null | sort -V | tail -1)
fi

mkdir -p "$OUT_DIR"
"$CLANG" -O2 -static -o "$OUT_DIR/libadbstitch.so" "$SRC_DIR/adbstitch.c"
echo "built: $OUT_DIR/libadbstitch.so"
