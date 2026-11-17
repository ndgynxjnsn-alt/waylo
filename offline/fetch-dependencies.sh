#!/usr/bin/env bash
# Requests every dependency from offline/dependencies.txt from a Maven repository, e.g. to check that
# an internal mirror (Nexus, Artifactory) has them all, or to make a proxy repository cache them.
#
#   offline/fetch-dependencies.sh                                      # Maven Central
#   REPO_URL=https://nexus.example.com/repository/maven-public offline/fetch-dependencies.sh
#   REPO_USER=me REPO_PASSWORD=secret REPO_URL=... offline/fetch-dependencies.sh
#
# Note: Gradle plugin markers (*.gradle.plugin) live in the Gradle Plugin Portal
# (https://plugins.gradle.org/m2), not in Maven Central.
set -uo pipefail

cd "$(dirname "$0")"
REPO_URL="${REPO_URL:-https://repo1.maven.org/maven2}"
LIST="${1:-dependencies.txt}"

auth=()
if [[ -n "${REPO_USER:-}" ]]; then
    auth=(--user "$REPO_USER:${REPO_PASSWORD:-}")
fi

failed=0
while IFS=: read -r group artifact version; do
    [[ -z "$group" || "$group" == \#* ]] && continue

    # Maven layout: group dots become directories, e.g. org.jetbrains.kotlin -> org/jetbrains/kotlin
    url="$REPO_URL/${group//.//}/$artifact/$version/$artifact-$version.pom"
    status=$(curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" "$url")

    echo "$status $group:$artifact:$version"
    [[ "$status" == 200 ]] || failed=$((failed + 1))
done < "$LIST"

echo "$failed request(s) did not return 200"
[[ $failed -eq 0 ]]
