#!/bin/bash
# android/run_v160.sh [seconds] [LARECOMP_X=1,...]: the game's v1.6.0 (android/run_ver.sh v160)
exec bash "$(dirname "$0")/run_ver.sh" v160 "$@"
