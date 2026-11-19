#!/usr/bin/env bash
# Run on the OFFLINE machine, inside the unpacked bundle (see offline/pack.sh).
#
# Makes the company Nexus able to serve everything this project needs to BUILD (the project itself
# is not published):
#
#   1. Gradle distribution -> raw hosted repository RAW_REPO, under gradle/distributions/
#   2. Dependencies and plugins: every file in the bundled Gradle cache (.jar, .pom, .module) is
#      looked up in the Maven group GROUP_REPO - exactly where builds resolve from. Whatever the group
#      cannot serve is uploaded into the Maven hosted repository HOSTED_REPO.
#
#      This includes the files Nexus mirrors tend to lack:
#        - pom-only artifacts: BOMs (spring-boot-dependencies, junit-bom, ...), parent POMs
#        - Gradle Module Metadata (.module), which Kotlin and Spring rely on for variant selection
#        - Gradle plugin markers (org.jetbrains.kotlin.jvm.gradle.plugin, org.springframework.boot
#          .gradle.plugin, ...): pom-only artifacts, normally from the Gradle Plugin Portal, that
#          map a plugin id from a `plugins { }` block to the real plugin jar
#
#      HOSTED_REPO must be a Maven 2 hosted repository that is a member of GROUP_REPO (a raw
#      repository cannot be part of a Maven group). The script re-checks every uploaded file via
#      GROUP_REPO and reports files the group still cannot serve.
#
# Usage:
#   NEXUS_URL=https://nexus.example.com NEXUS_USER=me NEXUS_PASSWORD=secret offline/nexus-upload.sh
#   DRY_RUN=1 NEXUS_URL=... offline/nexus-upload.sh        # only report what is missing
#
# Repository names default to the Nexus 3 defaults; Nexus has no raw repository by default, so
# RAW_REPO must be created once (Repository > Create repository > raw (hosted)).
#   GROUP_REPO=maven-public  HOSTED_REPO=maven-releases  RAW_REPO=raw-hosted
#
# Needs: curl.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${NEXUS_URL:?Set NEXUS_URL, e.g. NEXUS_URL=https://nexus.example.com}"
NEXUS_URL="${NEXUS_URL%/}"
GROUP_REPO="${GROUP_REPO:-maven-public}"
HOSTED_REPO="${HOSTED_REPO:-maven-releases}"
RAW_REPO="${RAW_REPO:-raw-hosted}"
DRY_RUN="${DRY_RUN:-}"

CACHE=".offline/gradle-home/caches/modules-2/files-2.1"
DIST_DIR=".offline/gradle-dist"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
[[ -d "$CACHE" ]] || { echo "No bundled Gradle cache at $CACHE - was this bundle made with offline/pack.sh?" >&2; exit 1; }

auth=()
if [[ -n "${NEXUS_USER:-}" ]]; then
    auth=(--user "$NEXUS_USER:${NEXUS_PASSWORD:-}")
fi

# HTTP status of a HEAD request (000 = no connection).
head_status() {
    curl -sS -o /dev/null -w '%{http_code}' --head "${auth[@]}" "$1" 2>/dev/null || true
}

# HTTP status of uploading a file with PUT (the Maven deploy protocol; also works for raw repos).
put_status() {
    curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" --upload-file "$1" "$2" 2>/dev/null || true
}

# Stops on answers that mean "wrong URL or credentials" rather than "file missing".
check_access() {
    local status=$1 url=$2
    case "$status" in
        000) echo "Cannot connect to $url - check NEXUS_URL." >&2; exit 1 ;;
        401) echo "401 Unauthorized for $url - check NEXUS_USER / NEXUS_PASSWORD." >&2; exit 1 ;;
        403) echo "403 Forbidden for $url - the user lacks read/write privileges on that repository." >&2; exit 1 ;;
    esac
}

# Ensures $2 (a URL in some repository) can be served; uploads local file $1 to $3 if not.
# Prints one of: present, uploaded, would-upload, failed:<status>
ensure() {
    local file=$1 check_url=$2 upload_url=$3 status
    status=$(head_status "$check_url")
    check_access "$status" "$check_url"
    if [[ "$status" == 200 ]]; then
        echo present
    elif [[ -n "$DRY_RUN" ]]; then
        echo would-upload
    else
        status=$(put_status "$file" "$upload_url")
        check_access "$status" "$upload_url"
        case "$status" in
            200 | 201 | 204) echo uploaded ;;
            *) echo "failed:$status" ;;
        esac
    fi
}

echo "Nexus:  $NEXUS_URL"
echo "Repos:  check $GROUP_REPO, upload Maven files to $HOSTED_REPO, Gradle to $RAW_REPO${DRY_RUN:+  (dry run)}"

# ---- 1. Gradle distribution -> raw ------------------------------------------------------------
echo
echo "==> Gradle distribution"
shopt -s nullglob
dist_files=("$DIST_DIR"/gradle-*.zip "$DIST_DIR"/gradle-*.zip.sha256)
shopt -u nullglob
[[ ${#dist_files[@]} -gt 0 ]] || { echo "No Gradle distribution in $DIST_DIR - was this bundle made with offline/pack.sh?" >&2; exit 1; }

for file in "${dist_files[@]}"; do
    url="$NEXUS_URL/repository/$RAW_REPO/gradle/distributions/$(basename "$file")"
    result=$(ensure "$file" "$url" "$url")
    echo "    $result  $url"
    if [[ "$result" == failed:404 ]]; then
        echo "    -> raw repository '$RAW_REPO' does not exist? Create it in Nexus (raw (hosted))." >&2
    fi
    [[ "$result" != failed:* ]] || exit 1
done
zip_name=$(basename "$(ls "$DIST_DIR"/gradle-*.zip | head -1)")
echo "    Wrapper URL: distributionUrl=$NEXUS_URL/repository/$RAW_REPO/gradle/distributions/$zip_name"

# ---- 2. Dependencies and plugins -> Maven hosted ----------------------------------------------
echo
echo "==> Dependencies and plugins ($(find "$CACHE" -type f | wc -l) files in the bundled Gradle cache)"
present=0 uploaded=0
missing=()   # files that could not be uploaded, or the group still cannot serve

while IFS= read -r file; do
    # The cache is laid out as <group>/<artifact>/<version>/<sha1>/<file name>; Maven repositories
    # as <group with / instead of .>/<artifact>/<version>/<file name>.
    IFS=/ read -r group artifact version _sha1 name <<< "${file#"$CACHE"/}"
    path="${group//.//}/$artifact/$version/$name"

    result=$(ensure "$file" "$NEXUS_URL/repository/$GROUP_REPO/$path" "$NEXUS_URL/repository/$HOSTED_REPO/$path")
    case "$result" in
        present) present=$((present + 1)) ;;
        would-upload) echo "    missing   $path"; missing+=("$path") ;;
        uploaded)
            uploaded=$((uploaded + 1))
            echo "    uploaded  $path"
            # The upload only helps if the group serves it (HOSTED_REPO must be a group member).
            status=$(head_status "$NEXUS_URL/repository/$GROUP_REPO/$path")
            if [[ "$status" != 200 ]]; then
                echo "    !! $GROUP_REPO still answers $status for $path - is $HOSTED_REPO a member of $GROUP_REPO?"
                missing+=("$path")
            fi
            ;;
        *) echo "    FAILED (${result#failed:})  $path"; missing+=("$path") ;;
    esac
done < <(find "$CACHE" -type f | sort)

echo
echo "==> Summary: $present already available, $uploaded uploaded, ${#missing[@]} missing"
if [[ ${#missing[@]} -gt 0 ]]; then
    printf '    %s\n' "${missing[@]}"
    [[ -n "$DRY_RUN" ]] && exit 0
    exit 1
fi
echo "    $GROUP_REPO can serve every file this project needs. Verify with: offline/build-with-nexus.sh"
