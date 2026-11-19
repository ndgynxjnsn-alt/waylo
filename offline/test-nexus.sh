#!/usr/bin/env bash
# End-to-end test of nexus-upload.sh and build-with-nexus.sh against a throwaway Sonatype Nexus in
# Docker. Everything runs on a Docker network WITHOUT internet access (--internal): Nexus' own
# maven-central proxy cannot fetch anything, and the build container can only reach Nexus.
#
#   offline/test-nexus.sh [bundle-dir]    # default: this checkout (run offline/pack.sh first)
#   KEEP=1 offline/test-nexus.sh          # leave Nexus running afterwards
#
# Steps: start Nexus with basic auth only (anonymous off) and a deploy user, create the raw
# repository, seed a few files (a mirror that has *some* things), run nexus-upload.sh (twice: the
# second run must upload nothing), check that a wrong password is refused, then build the project
# in a fresh container with an empty Gradle home - Gradle, plugins and dependencies all from Nexus.
#
# Default image: sonatype/nexus3:3.76.1, the last release without a click-through EULA. Newer
# Community Edition images refuse every request until their EULA is accepted. To test with one,
# read https://links.sonatype.com/products/nxrm/ce-eula and accept it yourself:
#   NEXUS_IMAGE=sonatype/nexus3:3.98.2 ACCEPT_NEXUS_EULA=yes offline/test-nexus.sh
set -euo pipefail

WORK=$(cd "${1:-$(dirname "$0")/..}" && pwd)
IMAGE="${NEXUS_IMAGE:-sonatype/nexus3:3.76.1}"
NAME=waylo-nexus-test
NET=waylo-nexus-test
BUILDER=waylo-offline-builder:local
ADMIN_PASSWORD=admin-test-password
export NEXUS_USER=waylo-deployer NEXUS_PASSWORD=deployer-test-password

cleanup() {
    if [[ -z "${KEEP:-}" ]]; then
        docker rm -f "$NAME" >/dev/null 2>&1 || true
        docker network rm "$NET" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

step() { echo; echo "######## $*"; }

step "Starting $IMAGE on internal network $NET (no internet)"
cleanup
docker network create --internal "$NET" >/dev/null
docker run -d --name "$NAME" --network "$NET" "$IMAGE" >/dev/null
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME")
export NEXUS_URL="http://$IP:8081"
echo "Nexus at $NEXUS_URL"

for _ in $(seq 1 120); do
    [[ $(curl -s -o /dev/null -w '%{http_code}' "$NEXUS_URL/service/rest/v1/status/writable") == 200 ]] && break
    sleep 3
done
[[ $(curl -s -o /dev/null -w '%{http_code}' "$NEXUS_URL/service/rest/v1/status/writable") == 200 ]] \
    || { echo "Nexus did not start" >&2; exit 1; }

step "Configuring Nexus: admin password, basic auth only, raw repository, deploy user"
initial=$(docker exec "$NAME" cat /nexus-data/admin.password)
curl -fsS -u "admin:$initial" -X PUT -H 'Content-Type: text/plain' --data "$ADMIN_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/security/users/admin/change-password"
api() { curl -fsS -u "admin:$ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }

if [[ "${ACCEPT_NEXUS_EULA:-}" == yes ]]; then
    eula=$(api "$NEXUS_URL/service/rest/v1/system/eula")
    api -X POST --data "${eula/\"accepted\" : false/\"accepted\" : true}" "$NEXUS_URL/service/rest/v1/system/eula"
fi

api -X PUT --data '{"enabled": false, "userId": "anonymous", "realmName": "NexusAuthorizingRealm"}' \
    "$NEXUS_URL/service/rest/v1/security/anonymous" >/dev/null
api -X POST "$NEXUS_URL/service/rest/v1/repositories/raw/hosted" --data '{
    "name": "raw-hosted", "online": true,
    "storage": {"blobStoreName": "default", "strictContentTypeValidation": false, "writePolicy": "allow_once"},
    "raw": {"contentDisposition": "ATTACHMENT"}}'
api -X POST "$NEXUS_URL/service/rest/v1/security/roles" --data '{
    "id": "waylo-deployer", "name": "waylo-deployer", "description": "read + deploy Maven and raw",
    "privileges": ["nx-repository-view-maven2-*-*", "nx-repository-view-raw-*-*"], "roles": []}' >/dev/null
api -X POST "$NEXUS_URL/service/rest/v1/security/users" --data '{
    "userId": "'"$NEXUS_USER"'", "firstName": "Waylo", "lastName": "Deployer", "emailAddress": "deployer@example.invalid",
    "password": "'"$NEXUS_PASSWORD"'", "status": "active", "roles": ["waylo-deployer"]}' >/dev/null
api "$NEXUS_URL/service/rest/v1/repositories/maven/group/maven-public" \
    | grep -o '"memberNames" : \[[^]]*\]'

step "Seeding a few artifacts (the mirror already serves some files)"
CACHE="$WORK/.offline/gradle-home/caches/modules-2/files-2.1"
find "$CACHE" -type f -name '*.jar' | sort | head -5 | while IFS= read -r file; do
    IFS=/ read -r group artifact version _sha1 name <<< "${file#"$CACHE"/}"
    path="${group//.//}/$artifact/$version/$name"
    curl -fsS -u "admin:$ADMIN_PASSWORD" --upload-file "$file" "$NEXUS_URL/repository/maven-releases/$path"
    echo "    seeded $path"
done

step "Wrong password must be refused"
if NEXUS_PASSWORD=wrong "$WORK/offline/nexus-upload.sh" >/dev/null 2>&1; then
    echo "FAIL: upload with a wrong password succeeded" >&2; exit 1
fi
echo "    refused, as expected"

step "nexus-upload.sh, first run"
"$WORK/offline/nexus-upload.sh" | grep -vE '^    uploaded '

step "nexus-upload.sh, second run: nothing left to upload"
second=$("$WORK/offline/nexus-upload.sh")
echo "$second" | tail -2
echo "$second" | grep -q ', 0 uploaded, 0 missing' || { echo "FAIL: second run uploaded something" >&2; exit 1; }

step "Building in a container without internet: Gradle, plugins and dependencies only from Nexus"
docker build -q -t "$BUILDER" - <<'EOF' >/dev/null
FROM eclipse-temurin:25-jdk
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*
EOF
docker run --rm --network "$NET" -u "$(id -u):$(id -g)" -e HOME=/tmp \
    -e NEXUS_URL -e NEXUS_USER -e NEXUS_PASSWORD -e NEXUS_GRADLE_USER_HOME=/tmp/empty-gradle-home \
    -v "$WORK:/work" -w /work "$BUILDER" \
    offline/build-with-nexus.sh --no-configuration-cache clean build 2>&1 \
    | grep -E '^==>|BUILD|FAILED|Could not|actionable|What went wrong|> '

step "PASSED"
