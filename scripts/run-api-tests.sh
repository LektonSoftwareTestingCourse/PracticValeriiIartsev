#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
COMPOSE=(docker compose --env-file /dev/null --project-name smp-api-tests -f docker-compose.api-tests.yaml)
EVIDENCE="$ROOT/services/api-tests/target/evidence"
RUNS=${API_TEST_RUNS:-1}
SETTINGS=(-s "$ROOT/services/api-tests/support/maven-settings.xml")
for argument in "$@"; do
    case "$argument" in
        -s|--settings|-s=*|--settings=*) SETTINGS=() ;;
    esac
done
mkdir -p "$EVIDENCE"

if [[ ! "$RUNS" =~ ^[1-9][0-9]*$ ]]; then
    echo 'API_TEST_RUNS must be a positive integer' >&2
    exit 2
fi

cleanup() {
    result=$?
    trap - EXIT
    "${COMPOSE[@]}" logs --no-color > "$EVIDENCE/compose.log" 2>&1 || true
    if [[ "${API_TEST_KEEP_STACK:-0}" != 1 ]]; then
        "${COMPOSE[@]}" down --volumes --remove-orphans --timeout 5 || true
    fi
    exit "$result"
}
trap cleanup EXIT

if [[ "${API_TEST_SKIP_BUILD:-0}" == 1 ]]; then
    "${COMPOSE[@]}" up -d --wait --wait-timeout 240
else
    "${COMPOSE[@]}" up -d --build --wait --wait-timeout 240
fi

for ((run = 1; run <= RUNS; run++)); do
    rm -f services/api-tests/target/surefire-reports/TEST-*.xml
    run_status=0
    "${MAVEN_BIN:-mvn}" "${SETTINGS[@]}" -B -f services/pom.xml -Papi-tests verify \
        -Dapi.manageStack=true "-Dapi.projectRoot=$ROOT" "$@" 2>&1 | tee "$EVIDENCE/run-$run.log" || run_status=$?
    mkdir -p "$EVIDENCE/run-$run"
    cp services/api-tests/target/surefire-reports/TEST-*.xml "$EVIDENCE/run-$run/" 2>/dev/null || true
    if [[ "$run_status" != 0 ]]; then
        exit "$run_status"
    fi
done
