#!/usr/bin/env bash
# Run on the INTERNET-CONNECTED Linux x64 machine.
#
# Collects everything needed to build this project without internet into
# dist/waylo-offline-bundle.tar.gz:
#   - .offline/gradle-dist/   the Gradle distribution zip named in gradle-wrapper.properties
#                             (+ its .sha256), checked against services.gradle.org
#   - .offline/gradle-home/   a project-local Gradle home holding every dependency and plugin,
#                             e.g. Kotlin, Spring Boot, Kaitai compiler (caches/modules-2), plus the
#                             unpacked distribution for the wrapper (wrapper/dists)
#   - the project sources
#
# On the offline machine, after unpacking:
#   offline/build-offline.sh   build from the bundled cache (needs a JDK 25+)
#   offline/nexus-upload.sh    fill the company Nexus with whatever it cannot serve
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_DIR="$PWD"
export GRADLE_USER_HOME="$PROJECT_DIR/.offline/gradle-home"
DIST_DIR="$PROJECT_DIR/.offline/gradle-dist"
BUNDLE="$PROJECT_DIR/dist/waylo-offline-bundle.tar.gz"

# ---- Gradle distribution ------------------------------------------------------------------------
# The wrapper deletes the zip after unpacking it, so it is fetched explicitly; it is uploaded to
# Nexus (raw) on the offline side. A zip from an earlier run is reused if its checksum still matches.
DIST_URL=$(sed -n 's/^distributionUrl=//p' gradle/wrapper/gradle-wrapper.properties | sed 's/\\:/:/g')
DIST_ZIP="$DIST_DIR/$(basename "$DIST_URL")"

echo "==> Gradle distribution: $(basename "$DIST_ZIP")"
mkdir -p "$DIST_DIR"
curl -fsSL -o "$DIST_ZIP.sha256" "$DIST_URL.sha256"
expected=$(cat "$DIST_ZIP.sha256")
if [[ -f "$DIST_ZIP" ]] && echo "$expected  $DIST_ZIP" | sha256sum --check --status; then
    echo "    already present, checksum OK"
else
    echo "    downloading $DIST_URL"
    curl -fSL --progress-bar -o "$DIST_ZIP" "$DIST_URL"
    echo "$expected  $DIST_ZIP" | sha256sum --check --quiet
    echo "    checksum OK"
fi

# ---- Dependencies and plugins -------------------------------------------------------------------
echo "==> Downloading plugins and dependencies into $GRADLE_USER_HOME"
rm -rf "$GRADLE_USER_HOME"
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
    --exclude=./.offline/nexus-gradle-home \
    --exclude='./build' --exclude='./*/build' \
    --transform 's|^\./|waylo/|' \
    .

echo "==> Done: $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"
echo "    Offline machine: tar -xzf $(basename "$BUNDLE"), then see waylo/README.md, 'Offline build'"
