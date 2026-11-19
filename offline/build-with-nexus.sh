#!/usr/bin/env bash
# Builds the project with EVERYTHING coming from the company Nexus, nothing from the bundled cache:
#   - Gradle itself from the raw repository RAW_REPO (uploaded by nexus-upload.sh)
#   - plugins and dependencies from the Maven group GROUP_REPO (via offline/nexus.init.gradle.kts)
#
# Proves that nexus-upload.sh made the Nexus complete, and shows how other machines / CI can build.
#
#   NEXUS_URL=https://nexus.example.com NEXUS_USER=me NEXUS_PASSWORD=secret offline/build-with-nexus.sh
#   ... offline/build-with-nexus.sh :gateway:bootJar      # other tasks; default: build
#
# Uses a separate, initially empty Gradle home (.offline/nexus-gradle-home) so nothing can come from
# the bundle. Needs: a JDK 25+, curl, unzip.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${NEXUS_URL:?Set NEXUS_URL, e.g. NEXUS_URL=https://nexus.example.com}"
export NEXUS_URL="${NEXUS_URL%/}"
export GROUP_REPO="${GROUP_REPO:-maven-public}"
RAW_REPO="${RAW_REPO:-raw-hosted}"
export GRADLE_USER_HOME="${NEXUS_GRADLE_USER_HOME:-$PWD/.offline/nexus-gradle-home}"

for tool in curl unzip java; do
    command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

auth=()
if [[ -n "${NEXUS_USER:-}" ]]; then
    auth=(--user "$NEXUS_USER:${NEXUS_PASSWORD:-}")
fi

# Same Gradle version as the wrapper, but downloaded from Nexus.
DIST_NAME=$(sed -n 's/^distributionUrl=.*\///p' gradle/wrapper/gradle-wrapper.properties)  # gradle-9.9.1-bin.zip
GRADLE_DIR="$GRADLE_USER_HOME/nexus-dists/$(echo "$DIST_NAME" | sed 's/-bin\.zip$//; s/-all\.zip$//')"

if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
    url="$NEXUS_URL/repository/$RAW_REPO/gradle/distributions/$DIST_NAME"
    echo "==> Downloading $url"
    mkdir -p "$GRADLE_USER_HOME/nexus-dists"
    curl -fsSL "${auth[@]}" -o "$GRADLE_USER_HOME/nexus-dists/$DIST_NAME" "$url"
    unzip -q -o "$GRADLE_USER_HOME/nexus-dists/$DIST_NAME" -d "$GRADLE_USER_HOME/nexus-dists"
fi

if [[ $# -eq 0 ]]; then
    set -- build
fi

echo "==> $GRADLE_DIR/bin/gradle $* (plugins and dependencies from $NEXUS_URL/repository/$GROUP_REPO)"
exec "$GRADLE_DIR/bin/gradle" --init-script offline/nexus.init.gradle.kts "$@"
