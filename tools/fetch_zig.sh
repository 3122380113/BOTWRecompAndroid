#!/bin/bash
# The compiler the start screen builds the game code with on the player's machine (runtime/gamebuild.c): zig (MIT,
# its clang / lld: Apache-2.0 with LLVM exception), as `zig cc`. Fetches the pinned release for this OS / CPU (or
# ZIG_TARGET=<arch>-<os>, e.g. x86_64-windows) into build/zig/, checks its hash and keeps only what compiling C for
# that same machine needs. Shipped as Contents/Resources/zig (macOS), zig/ next to the exe (Windows, Linux).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=0.17.0
case "$(uname -s)" in Darwin) OS=macos;; Linux) OS=linux;; *) OS=windows;; esac
case "$(uname -m)" in arm64|aarch64) ARCH=aarch64;; *) ARCH=x86_64;; esac
TARGET=${ZIG_TARGET:-$ARCH-$OS}
EXT=tar.xz; [ "${TARGET#*-}" = windows ] && EXT=zip
NAME=zig-$TARGET-$VER
# sha256 of each release archive (ziglang.org/download/index.json; checked against it when pinned)
case "$TARGET" in
    aarch64-macos)  SHA= ;;
    x86_64-linux)   SHA= ;;
    aarch64-linux)  SHA= ;;
    x86_64-windows) SHA= ;;
    *) echo "no zig for $TARGET"; exit 1;;
esac
[ -n "$SHA" ] || { echo "tools/fetch_zig.sh: pin the sha256 of $NAME.$EXT first"; exit 1; }

DL=build/zig-dl
mkdir -p "$DL"
[ -f "$DL/$NAME.$EXT" ] || curl -fL --proto '=https' -o "$DL/$NAME.$EXT" "https://ziglang.org/download/$VER/$NAME.$EXT"
GOT=$( (shasum -a 256 2>/dev/null || sha256sum) < "$DL/$NAME.$EXT" | cut -d' ' -f1)
[ "$GOT" = "$SHA" ] || { echo "$NAME.$EXT: sha256 $GOT, expected $SHA"; exit 1; }
rm -rf "$DL/$NAME" build/zig
if [ "$EXT" = zip ]; then unzip -q "$DL/$NAME.$EXT" -d "$DL"; else tar -xJf "$DL/$NAME.$EXT" -C "$DL"; fi
mv "$DL/$NAME" build/zig

# size: the C headers / libc sources of the other targets aren't needed (the game code is built for the machine it
# runs on)
L=build/zig/lib
case "${TARGET#*-}" in
    macos)   keep='any-macos|aarch64-macos|x86_64-macos' ;;
    linux)   keep="generic-glibc|${TARGET%-*}-linux|any-linux|generic-musl" ;;
    windows) keep='any-windows|x86_64-windows|mingw' ;;
esac
for d in "$L"/libc/include/*; do basename "$d" | grep -qE "$keep|^generic-|^any-" || rm -rf "$d"; done
rm -rf "$L/docs" build/zig/doc  # (lib/std stays: zig cc builds its compiler_rt from it)
echo "build/zig: zig $VER for $TARGET, $(du -sh build/zig | cut -f1)"
