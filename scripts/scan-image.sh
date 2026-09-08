#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Karyo WMS - local reproduction of the CI `scan` job
#
# Builds the karyo-app fast-jar and image, then scans it under the SAME GATE
# .github/workflows/ci.yml applies, so a dependency bump can be measured before it
# is pushed. The invocation is not byte-identical - CI renders its table from a
# single `trivy image` run, this scans once to JSON and renders the gate from that
# report with `trivy convert` (see the note on that command below). What IS copied
# from the workflow deliberately is the equivalence that matters: the same severity
# filter, the same --exit-code 1, and the same `:ro,Z` mount. They are the gate, not
# a knob, and this script must not be more permissive than CI.
#
# Usage:
#   ./scripts/scan-image.sh                 build, scan, print the table
#   ./scripts/scan-image.sh --json out.json build, scan, also write raw JSON
#   ./scripts/scan-image.sh --no-build      scan the last image this script built
#
# Notes:
#   * --pull=always on the image build. The base tag (eclipse-temurin:21-jre-alpine)
#     floats, and a stale local base makes the report worse than CI's for reasons
#     that have nothing to do with the repository. Measured 2026-09-05: a cached
#     base reported 7 CRITICAL / 64 HIGH where a freshly pulled one reported
#     2 CRITICAL / 36 HIGH on the identical tree.
#   * `:ro,Z` on the bind mount, not `:ro`. SELinux is Enforcing on the CI host and
#     on Fedora workstations; a bare :ro is denied inside the scanning container and
#     Trivy dies with a message that reads like a file-permission problem. See the
#     comment on the same step in .github/workflows/ci.yml.
#   * --timestamp 0, matching the image CI builds. quarkusBuild restamps every
#     dependency jar with the build time, so without it the 94 MiB lib/ layer gets a
#     fresh digest on every build and layer deduplication stops working. It is not a
#     scan concern, but this script must build the image CI builds, not a variant of
#     it, or the report describes something nobody ships.
#   * JAVA_HOME must point at a JDK. A system JRE has no javac and Gradle will fail.
# ============================================================================

IMAGE_TAG="karyo-app:local-scan"
BUILD=1
JSON_OUT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        --json) JSON_OUT="${2:?--json needs a path}"; shift 2 ;;
        -h|--help) sed -n '4,/^# ===/p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [[ "$BUILD" -eq 1 ]]; then
    echo "==> Building karyo-app fast-jar"
    ./gradlew :services:karyo-app:quarkusBuild -Dquarkus.profile=prod

    echo "==> Building image ${IMAGE_TAG}"
    podman build --pull=always --timestamp 0 \
        -f infrastructure/docker/Dockerfile.service \
        --build-arg SERVICE_DIR=services/karyo-app \
        -t "$IMAGE_TAG" .
fi

echo "==> Exporting image"
podman save -o "$WORKDIR/image.tar" "$IMAGE_TAG"

echo "==> Running Trivy (CRITICAL,HIGH)"
# One scan, two renderings. The image is scanned once into JSON and the gating table
# is rendered from that same report with `trivy convert`, so the saved JSON and the
# pass/fail table can never describe different scans - a vulnerability-DB refresh
# landing between two separate scans of the same tar would otherwise do exactly that.
# `convert` reads the report and needs no DB, but it does need `--scanners`: that flag
# tells it which scanners produced the report, and without it the summary table is
# silently dropped with a "No enabled scanners found" WARN. It has to name BOTH, since
# `trivy image` defaults to vuln,secret - naming only vuln labels every secret-scanned
# target "-", which the table's own legend reads as "Not scanned". The severity filter
# and --exit-code 1 are still the gate and still match CI's.
podman run --rm \
    -v "$WORKDIR/image.tar:/scan/image.tar:ro,Z" \
    -v trivy-db:/root/.cache/trivy \
    docker.io/aquasec/trivy:latest image \
        --input /scan/image.tar \
        --severity CRITICAL,HIGH \
        --format json > "$WORKDIR/report.json"

if [[ -n "$JSON_OUT" ]]; then
    cp "$WORKDIR/report.json" "$JSON_OUT"
    echo "==> JSON written to $JSON_OUT"
fi

podman run --rm \
    -v "$WORKDIR/report.json:/scan/report.json:ro,Z" \
    docker.io/aquasec/trivy:latest convert \
        --scanners vuln,secret \
        --severity CRITICAL,HIGH \
        --exit-code 1 \
        --format table \
        /scan/report.json
