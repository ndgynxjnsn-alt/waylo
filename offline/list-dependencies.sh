#!/usr/bin/env bash
# Prints every module in the offline Gradle cache as group:artifact:version, one per line.
# Includes plugins (e.g. org.jetbrains.kotlin.jvm.gradle.plugin) and transitive dependencies.
set -euo pipefail

cd "$(dirname "$0")/.."
CACHE="${GRADLE_USER_HOME:-$PWD/.offline/gradle-home}/caches/modules-2/files-2.1"

# The cache is laid out as files-2.1/<group>/<artifact>/<version>/<sha1>/<file>
find "$CACHE" -mindepth 3 -maxdepth 3 -type d \
    | sed "s|^$CACHE/||; s|/|:|g" \
    | sort
