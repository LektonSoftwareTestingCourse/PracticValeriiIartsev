#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
COMPOSE=(docker compose --env-file /dev/null -p smp-api-tests -f "$ROOT/docker-compose.api-tests.yaml")
EVIDENCE="$ROOT/services/api-tests/target/evidence/mutations"
SETTINGS=(-s "$ROOT/services/api-tests/support/maven-settings.xml")
for argument in "$@"; do
    case "$argument" in
        -s|--settings|-s=*|--settings=*) SETTINGS=() ;;
    esac
done
WORK=$(mktemp -d "${TMPDIR:-/tmp}/smp-api-mutations.XXXXXX")
mkdir -p "$EVIDENCE"
: > "$EVIDENCE/summary.jsonl"

cleanup() {
    result=$?
    trap - EXIT
    # Restore the original image even if building or testing the mutant fails.
    "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 120 transaction-logger \
        > "$EVIDENCE/restore.log" 2>&1 || result=1
    if [[ "${API_TEST_KEEP_STACK:-0}" != 1 ]]; then
        "${COMPOSE[@]}" down --volumes --timeout 5 >> "$EVIDENCE/restore.log" 2>&1 || result=1
    fi
    rm -rf -- "$WORK"
    exit "$result"
}
trap cleanup EXIT

"${COMPOSE[@]}" up -d --build --wait --wait-timeout 240 > "$EVIDENCE/stack.log" 2>&1
python3 - "$ROOT" "$WORK" <<'PY'
import pathlib
import shutil
import sys
root, work = map(pathlib.Path, sys.argv[1:])
shutil.copytree(root / 'services', work / 'services',
                ignore=shutil.ignore_patterns('target', 'node_modules', '.env*', '__pycache__'))
PY

expect_assertion_failure() {
    name=$1
    report=$2
    shift 2
    rm -f -- "$report"
    set +e
    "$@" > "$EVIDENCE/$name.log" 2>&1
    status=$?
    set -e
    python3 - "$status" "$report" "$name" "$EVIDENCE" <<'PY'
import json
import pathlib
import sys
import xml.etree.ElementTree as ET
status, report, name, evidence = sys.argv[1:]
suite = ET.parse(report).getroot()
record = dict(name=name, tests=int(suite.get('tests')), failures=int(suite.get('failures')),
              errors=int(suite.get('errors')), exit_code=int(status))
assert record['exit_code'] != 0 and record['failures'] > 0 and record['errors'] == 0, record
path = pathlib.Path(evidence)
(path / (name + '.xml')).write_bytes(pathlib.Path(report).read_bytes())
with (path / 'summary.jsonl').open('a') as out:
    out.write(json.dumps(record) + '\n')
print(json.dumps(record))
PY
}

MVN=("${MAVEN_BIN:-mvn}" "${SETTINGS[@]}" -B -f "$WORK/services/pom.xml" -Papi-tests)
REPORTS="$WORK/services/api-tests/target/surefire-reports"
expect_assertion_failure approved-code "$REPORTS/TEST-com.processing.api.GatewayApiTest.xml" \
    "${MVN[@]}" test '-Dtest=GatewayApiTest#shouldApproveValidTransactionAndMatchSchema' \
    -Dapi.expectedApprovedCode=99 "$@"

python3 - "$WORK" <<'PY'
import json
import pathlib
import sys
p = pathlib.Path(sys.argv[1]) / 'services/api-tests/src/test/resources/schemas/authorization-response.json'
schema = json.loads(p.read_text())
schema['properties']['stan']['pattern'] = '^[0-9]{5}$'
p.write_text(json.dumps(schema))
PY
expect_assertion_failure schema-stan-length "$REPORTS/TEST-com.processing.api.GatewayApiTest.xml" \
    "${MVN[@]}" test '-Dtest=GatewayApiTest#shouldApproveValidTransactionAndMatchSchema' "$@"

python3 - "$WORK" <<'PY'
import pathlib
import sys
p = pathlib.Path(sys.argv[1]) / ('services/transaction-logger/src/main/java/com/processing/'
                              'transactionlogger/mapper/TransactionMapper.java')
text = p.read_text()
old = 'transaction.setId(request.id());'
assert text.count(old) == 1
p.write_text(text.replace(old, 'transaction.setId(java.util.UUID.randomUUID());'))
PY
docker build -f "$ROOT/services/transaction-logger/Dockerfile" \
    -t smp-api/transaction-logger:dedup-mutant "$WORK" > "$EVIDENCE/build-mutant.log" 2>&1
cat > "$WORK/mutant.yaml" <<'YAML'
services:
  transaction-logger:
    image: smp-api/transaction-logger:dedup-mutant
YAML
"${COMPOSE[@]}" -f "$WORK/mutant.yaml" up -d --no-deps --force-recreate --wait --wait-timeout 120 \
    transaction-logger > "$EVIDENCE/start-mutant.log" 2>&1
expect_assertion_failure logger-deduplication "$REPORTS/TEST-com.processing.api.LoggerApiTest.xml" \
    "${MVN[@]}" test '-Dtest=LoggerApiTest#shouldNotDuplicateTransactionOnRedelivery' "$@"
