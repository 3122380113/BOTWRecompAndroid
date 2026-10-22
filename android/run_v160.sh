#!/bin/bash
# android/run_v160.sh [seconds] [LARECOMP_X=1,...]: android/run_boot.sh for the game's v1.6.0, kept beside v1.0.0 - its
# files in <external files>/v160/extracted (android/push_game.sh with GAME_DEST and GAMELIB_OUT=android/out/v160), its
# game code copied (run-as) into the app's internal storage, since Android loads no code from external storage, and
# both handed to the runtime (LARECOMP_DATA_ROOT, LARECOMP_GAME_LIB). Saves and settings live in that data root too.
set -euo pipefail
export MSYS_NO_PATHCONV=1  # (Git Bash: leave the device paths alone)
cd "$(dirname "$0")/.."
PKG=com.botwrecomp.android
EXT=/sdcard/Android/data/$PKG/files/v160
INT=/data/data/$PKG/files/v160
adb shell am force-stop $PKG
adb shell run-as $PKG mkdir -p files/v160
# (run-as may not read external storage, nor take a redirect: the shell reads it and pipes it in)
for f in botwgame.so botwgame.so.id; do adb shell "cat $EXT/extracted/$f | run-as $PKG sh -c 'cat > files/v160/$f'"; done
# (no save there yet: the runtime starts it as a copy of the player's v1.0.0 save - LARECOMP_SAVE_SEED)
bash android/run_boot.sh "${1:-120}" \
    "LARECOMP_DATA_ROOT=$EXT,LARECOMP_GAME_LIB=$INT/botwgame.so,LARECOMP_SAVE_SEED=/sdcard/Android/data/$PKG/files/save${2:+,$2}"
