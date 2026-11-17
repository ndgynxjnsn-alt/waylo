#!/usr/bin/env bash
# Run on the INTERNET-CONNECTED Linux x64 machine.
#
# Builds the project once with a dedicated, project-local Gradle home (.offline/gradle-home), so that
# afterwards it contains exactly what this build needs:
#   - the Gradle distribution used by the wrapper (wrapper/dists)
#   - every dependency and every plugin, e.g. Kotlin, Spring Boot, Kaitai compiler (caches/modules-2)
# Then packs sources + that Gradle home into dist/waylo-offline-bundle.tar.gz.
#
# On the offline machine: unpack, then run offline/build-offline.sh (needs a JDK 25+ installed).
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_DIR="$PWD"
export GRADLE_USER_HOME="$PROJECT_DIR/.offline/gradle-home"
BUNDLE="$PROJECT_DIR/dist/waylo-offline-bundle.tar.gz"

echo "==> Downloading Gradle, plugins and dependencies into $GRADLE_USER_HOME"
rm -rf "$PROJECT_DIR/.offline"
# --rerun-tasks: up-to-date tasks would skip downloads they only need while running (Kotlin compiler, ...)
./gradlew --no-daemon --no-configuration-cache --rerun-tasks resolveAllDependencies build

echo "==> Trimming the Gradle home to its relocatable parts"
# Gradle documents caches/modules-2 (minus lock files) as copyable to another machine. Everything
# else (compiled build scripts, transforms, daemon logs, ...) is recreated on the offline machine.
find "$GRADLE_USER_HOME" -mindepth 1 -maxdepth 1 ! -name wrapper ! -name caches -exec rm -rf {} +
find "$GRADLE_USER_HOME/caches" -mindepth 1 -maxdepth 1 ! -name modules-2 -exec rm -rf {} +
find "$GRADLE_USER_HOME/caches/modules-2" \( -name '*.lock' -o -name gc.properties \) -delete
find "$GRADLE_USER_HOME/wrapper" -name '*.lck' -delete

echo "==> Writing the flat dependency list (offline/dependencies.txt)"
offline/list-dependencies.sh > offline/dependencies.txt

echo "==> Packing $BUNDLE"
mkdir -p "$PROJECT_DIR/dist"
tar -czf "$BUNDLE" \
    --exclude=./dist --exclude=./graf_zahl --exclude=./.gradle --exclude=./.kotlin --exclude=./.idea \
    --exclude='./build' --exclude='./*/build' \
    --transform 's|^\./|waylo/|' \
    .

echo "==> Done: $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"
echo "    Offline machine: tar -xzf $(basename "$BUNDLE") && waylo/offline/build-offline.sh"
