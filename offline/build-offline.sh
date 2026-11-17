#!/usr/bin/env bash
# Run on the OFFLINE machine, inside the unpacked bundle (see offline/pack.sh).
# Needs a JDK 25+ on the PATH (or JAVA_HOME). Everything else comes from .offline/gradle-home.
#
#   offline/build-offline.sh                    # = ./gradlew --offline build
#   offline/build-offline.sh :gateway:bootJar   # any other tasks
set -euo pipefail

cd "$(dirname "$0")/.."
export GRADLE_USER_HOME="$PWD/.offline/gradle-home"

if [[ ! -d "$GRADLE_USER_HOME/caches/modules-2" ]]; then
    echo "No offline Gradle cache at $GRADLE_USER_HOME - was this bundle made with offline/pack.sh?" >&2
    exit 1
fi

if [[ $# -eq 0 ]]; then
    set -- build
fi

exec ./gradlew --offline "$@"
