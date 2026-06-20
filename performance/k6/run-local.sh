#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K6_DIR="$ROOT_DIR/performance/k6"
RESULTS_DIR="$ROOT_DIR/performance/results"
MOCK_COMPOSE="$ROOT_DIR/performance/mock-external/docker-compose.performance.yml"
NODE_MOCK="$ROOT_DIR/performance/mock-external/gemini/node-mock/server.js"
VERIFY_AI="$ROOT_DIR/performance/verify/verify_ai_call_count.sh"
K6_VERSION_FILE="$K6_DIR/K6_VERSION"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.performance}"
NODE_MOCK_PID_FILE="/tmp/didimlog-performance/gemini-mock.pid"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

: "${BASE_URL:=http://localhost:8080}"
: "${WIREMOCK_URL:=http://localhost:8090}"
: "${MOCK_GEMINI_MODE:=auto}"
: "${MOCK_GEMINI_DELAY_MS:=500}"
: "${MONGO_URI:=mongodb://localhost:27017/didimlog-performance}"
: "${MONGO_HOST:=127.0.0.1}"
: "${MONGO_PORT:=27017}"
: "${PERF_BOJ_ID:=perfuser}"
: "${PERF_AI_BOJ_ID_PREFIX:=${PERF_BOJ_ID}_ai}"
: "${PERF_PASSWORD:=PerfPassword123!}"
: "${PERF_BCRYPT_PASSWORD:=\$2y\$10\$FTcPZSUl3qvlezqQQb7oreLZ8T2XID88ICjFjXipc2Ei4EfS7k9SO}"
: "${PERF_FIXTURE_RETROSPECTIVES:=100}"
: "${AI_REPEAT_COUNT:=10}"
: "${AI_CONCURRENCY:=50}"
: "${AI_POLL_TIMEOUT_SECONDS:=30}"
: "${AI_POLL_INTERVAL_MILLIS:=250}"
: "${AI_FAILED_POLL_TIMEOUT_SECONDS:=20}"
: "${AI_COMPLETED_POLL_TIMEOUT_SECONDS:=30}"
: "${FAIL_FAST_AI_REPEAT:=false}"
: "${REDIS_HOST:=localhost}"
: "${REDIS_PORT:=6379}"
: "${RATE_LIMIT_SIGNUP_IP:=10.67.1.11}"
: "${RATE_LIMIT_LOGIN_IP:=10.67.1.12}"
: "${RATE_LIMIT_PASSWORD_RESET_IP:=10.67.1.13}"
: "${TARGET_ENVIRONMENT:=local}"
: "${ALLOW_REMOTE_LOAD_TEST:=false}"
: "${REMOTE_TARGET_ALLOWLIST:=}"
: "${COMMIT_SHA:=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo NOT_CAPTURED)}"
: "${JAVA_VERSION:=$(java -version 2>&1 | head -n 1 || echo NOT_CAPTURED)}"
: "${KOTLIN_VERSION:=1.9.25}"
: "${K6_RUN_ID:=perf-$(date +%Y%m%d%H%M%S)}"
: "${JVM_HEAP:=${JAVA_TOOL_OPTIONS:-NOT_CAPTURED}}"
: "${CPU_INFO:=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | head -n 1 || echo NOT_CAPTURED)}"
: "${MEMORY_INFO:=$(sysctl -n hw.memsize 2>/dev/null || grep MemTotal /proc/meminfo 2>/dev/null || echo NOT_CAPTURED)}"

K6_EXPECTED_VERSION="$(tr -d '[:space:]' <"$K6_VERSION_FILE")"
GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR" status --porcelain 2>/dev/null)" ]] && echo true || echo false)"

export BASE_URL WIREMOCK_URL MOCK_GEMINI_DELAY_MS MONGO_URI PERF_BOJ_ID PERF_AI_BOJ_ID_PREFIX
export RATE_LIMIT_SIGNUP_IP RATE_LIMIT_LOGIN_IP RATE_LIMIT_PASSWORD_RESET_IP
export COMMIT_SHA JAVA_VERSION KOTLIN_VERSION K6_RUN_ID JVM_HEAP CPU_INFO MEMORY_INFO
export K6_VERSION="$K6_EXPECTED_VERSION"
export GIT_DIRTY
export MONGO_ENVIRONMENT="${MONGO_ENVIRONMENT:-local}"
export REDIS_ENVIRONMENT="${REDIS_ENVIRONMENT:-local}"
export FIXTURE_COUNT="${FIXTURE_COUNT:-$PERF_FIXTURE_RETROSPECTIVES}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

port_open() {
  local host="$1"
  local port="$2"
  python3 - "$host" "$port" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.settimeout(0.3)
    sys.exit(0 if sock.connect_ex((host, port)) == 0 else 1)
PY
}

docker_compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  docker-compose "$@"
}

assert_safe_environment() {
  python3 - "$BASE_URL" "$WIREMOCK_URL" "$MONGO_URI" "$REDIS_HOST" "$TARGET_ENVIRONMENT" "$ALLOW_REMOTE_LOAD_TEST" "$REMOTE_TARGET_ALLOWLIST" <<'PY'
import sys
from urllib.parse import urlparse

base_url, wiremock_url, mongo_uri, redis_host, target_env, allow_remote, remote_allowlist = sys.argv[1:]
local_hosts = {"localhost", "127.0.0.1", "::1", "mongo", "redis", "gemini-wiremock", "host.docker.internal"}

def fail(message):
    raise SystemExit(message)

target_env = target_env.lower()
if target_env in {"prod", "production"}:
    fail("TARGET_ENVIRONMENT=prod/production is blocked")

def parse_http(name, value):
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        fail(f"{name} must be an http(s) URL")
    if parsed.username or parsed.password:
        fail(f"{name} must not contain URL credentials")
    return parsed

def remote_base_allowed(parsed):
    if target_env != "staging" or allow_remote.lower() != "true":
        return False
    if parsed.scheme != "https":
        fail("Remote staging BASE_URL must use HTTPS")
    allowed = {item.strip() for item in remote_allowlist.split(",") if item.strip()}
    if parsed.hostname not in allowed:
        fail(f"BASE_URL host is not in REMOTE_TARGET_ALLOWLIST: {parsed.hostname}")
    return True

base = parse_http("BASE_URL", base_url)
if base.hostname not in local_hosts and not remote_base_allowed(base):
    fail(f"BASE_URL host is not allowed: {base.hostname}")

wiremock = parse_http("WIREMOCK_URL", wiremock_url)
if wiremock.hostname not in local_hosts:
    fail(f"WIREMOCK_URL must be local: {wiremock.hostname}")

mongo = urlparse(mongo_uri)
if mongo.scheme != "mongodb":
    fail("MONGO_URI must use mongodb://")
if mongo.username or mongo.password:
    fail("MONGO_URI must not contain credentials")
db_name = mongo.path.lstrip("/").split("?")[0]
if db_name != "didimlog-performance":
    fail(f"MONGO_URI database must be didimlog-performance: {db_name or '<missing>'}")
if mongo.hostname not in local_hosts:
    fail(f"MONGO_URI host must be local: {mongo.hostname}")

if redis_host not in local_hosts:
    fail(f"REDIS_HOST must be local: {redis_host}")
PY
}

assert_local_fixture_environment() {
  assert_safe_environment
  python3 - "$BASE_URL" "$MONGO_URI" <<'PY'
import sys
from urllib.parse import urlparse

base = urlparse(sys.argv[1])
mongo = urlparse(sys.argv[2])
local_hosts = {"localhost", "127.0.0.1", "::1", "mongo", "host.docker.internal"}
if base.hostname not in local_hosts:
    raise SystemExit("Fixture operations require a local BASE_URL")
if mongo.hostname not in local_hosts or mongo.path.lstrip("/").split("?")[0] != "didimlog-performance":
    raise SystemExit("Fixture operations require local didimlog-performance MongoDB")
PY
}

validate_number_config() {
  python3 - "$AI_CONCURRENCY" "$AI_REPEAT_COUNT" "$MOCK_GEMINI_DELAY_MS" "${READ_VUS:-10}" "${RATE_LIMIT_OVERAGE_REQUESTS:-2}" "${READ_PAGE_SIZE:-10}" <<'PY'
import re
import sys

checks = [
    ("AI_CONCURRENCY", sys.argv[1], 1, 500),
    ("AI_REPEAT_COUNT", sys.argv[2], 1, 100),
    ("MOCK_GEMINI_DELAY_MS", sys.argv[3], 0, 30000),
    ("READ_VUS", sys.argv[4], 1, 500),
    ("RATE_LIMIT_OVERAGE_REQUESTS", sys.argv[5], 1, 20),
    ("READ_PAGE_SIZE", sys.argv[6], 1, 100),
]
for name, value, low, high in checks:
    if not re.fullmatch(r"0|[1-9]\d*", str(value)):
        raise SystemExit(f"{name} must be an integer: {value}")
    parsed = int(value)
    if parsed < low or parsed > high:
        raise SystemExit(f"{name} must be between {low} and {high}: {value}")
PY
}

safe_boj_token() {
  printf '%s' "$1" | tr -c '[:alnum:]_' '_'
}

ai_boj_id_for() {
  local suffix="$1"
  printf '%s_%s' "$(safe_boj_token "$PERF_AI_BOJ_ID_PREFIX")" "$(safe_boj_token "$suffix")"
}

start_mocks() {
  assert_local_fixture_environment
  validate_number_config
  case "$MOCK_GEMINI_MODE" in
    auto)
      if command -v docker >/dev/null 2>&1; then
        if docker_compose -f "$MOCK_COMPOSE" up -d; then
          if wait_for_mock_admin 30; then
            configure_wiremock
            return 0
          fi
          echo "WireMock health check failed in auto mode; falling back to Node Gemini mock." >&2
          stop_wiremock_container || true
          start_local_gemini_mock
          configure_wiremock
          return 0
        fi
        echo "Docker Compose startup failed in auto mode; falling back to local services." >&2
      fi
      start_local_mongo
      start_local_redis
      start_local_gemini_mock
      configure_wiremock
      ;;
    wiremock)
      require_command docker
      docker_compose -f "$MOCK_COMPOSE" up -d
      wait_for_mock_admin 30
      configure_wiremock
      ;;
    node)
      start_local_mongo
      start_local_redis
      start_local_gemini_mock
      configure_wiremock
      ;;
    *)
      echo "MOCK_GEMINI_MODE must be auto, wiremock, or node. value=$MOCK_GEMINI_MODE" >&2
      return 2
      ;;
  esac
}

start_local_mongo() {
  if port_open "$MONGO_HOST" "$MONGO_PORT"; then
    return 0
  fi
  require_command mongod
  local data_dir="${PERF_LOCAL_MONGO_DATA_DIR:-/tmp/didimlog-performance/mongo}"
  local log_file="${PERF_LOCAL_MONGO_LOG:-/tmp/didimlog-performance/mongod.log}"
  mkdir -p "$data_dir" "$(dirname "$log_file")"
  mongod --dbpath "$data_dir" --bind_ip "$MONGO_HOST" --port "$MONGO_PORT" --logpath "$log_file" --fork >/dev/null
}

start_local_redis() {
  if redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping >/dev/null 2>&1; then
    return 0
  fi
  require_command redis-server
  redis-server --bind "$REDIS_HOST" --port "$REDIS_PORT" --save "" --appendonly no --daemonize yes >/dev/null
}

start_local_gemini_mock() {
  local port="${WIREMOCK_URL##*:}"
  port="${port%%/*}"
  if wait_for_mock_admin 1 >/dev/null 2>&1; then
    return 0
  fi
  require_command node
  mkdir -p /tmp/didimlog-performance
  node --check "$NODE_MOCK" >/dev/null
  MOCK_GEMINI_DELAY_MS="$MOCK_GEMINI_DELAY_MS" WIREMOCK_PORT="$port" nohup node "$NODE_MOCK" >/tmp/didimlog-performance/gemini-mock.log 2>&1 &
  echo $! >"$NODE_MOCK_PID_FILE"
  wait_for_mock_admin 30
}

wait_for_mock_admin() {
  local timeout_seconds="${1:-30}"
  local deadline=$(( $(date +%s) + timeout_seconds ))
  require_command curl
  while true; do
    if curl -fsS -X POST "$WIREMOCK_URL/__admin/requests/count" \
      -H "Content-Type: application/json" \
      -d '{"method":"POST","urlPathPattern":"/v1beta/models/.*:generateContent"}' >/dev/null 2>&1; then
      return 0
    fi
    if [[ "$(date +%s)" -ge "$deadline" ]]; then
      break
    fi
    sleep 0.5
  done
  echo "Gemini mock admin API is not healthy at $WIREMOCK_URL" >&2
  return 1
}

stop_wiremock_container() {
  if command -v docker >/dev/null 2>&1; then
    docker rm -f didimlog-performance-gemini-wiremock >/dev/null 2>&1 || true
  fi
}

configure_wiremock() {
  assert_local_fixture_environment
  require_command curl
  curl -fsS -X POST "$WIREMOCK_URL/__admin/settings" \
    -H "Content-Type: application/json" \
    -d "{\"fixedDelay\":$MOCK_GEMINI_DELAY_MS}" >/dev/null
  curl -fsS -X DELETE "$WIREMOCK_URL/__admin/requests" >/dev/null
  curl -fsS -X POST "$WIREMOCK_URL/__admin/scenarios/reset" >/dev/null
}

seed_fixture() {
  assert_local_fixture_environment
  validate_number_config
  if command -v mongosh >/dev/null 2>&1; then
    seed_fixture_with_mongosh
    return
  fi
  seed_fixture_with_mongoimport
}

seed_fixture_with_mongosh() {
  mongosh "$MONGO_URI" --quiet --eval "
const studentId = 'perf-student-1';
const bojId = '$PERF_BOJ_ID';
const now = new Date();
db.students.updateOne(
  { _id: studentId },
  {
    \$set: {
      nickname: 'perfuser',
      provider: 'BOJ',
      providerId: bojId,
      email: 'perfuser@example.test',
      bojId: bojId,
      password: '$PERF_BCRYPT_PASSWORD',
      rating: 0,
      solvedAcTierLevel: 0,
      currentTier: 'UNRATED',
      role: 'USER',
      termsAgreed: true,
      isVerified: true,
      consecutiveSolveDays: 0,
      primaryLanguage: 'JAVA',
      isOnboardingFinished: true,
      createdAt: now
    }
  },
  { upsert: true }
);

db.retrospectives.deleteMany({ studentId: studentId, problemId: { \$regex: '^perf-' } });
const docs = [];
for (let i = 1; i <= Number('$PERF_FIXTURE_RETROSPECTIVES'); i++) {
  docs.push({
    _id: 'perf-retro-' + i,
    studentId: studentId,
    problemId: 'perf-' + i,
    content: '성능 테스트용 회고 내용입니다. 인덱스와 페이징 조회를 확인합니다. #' + i,
    summary: '성능 fixture ' + i,
    createdAt: new Date(now.getTime() - i * 3600 * 1000),
    isBookmarked: i % 10 === 0,
    solutionResult: i % 4 === 0 ? 'FAIL' : 'SUCCESS',
    solvedCategory: i % 3 === 0 ? 'DP' : 'Implementation',
    solveTime: String(300 + i)
  });
}
if (docs.length > 0) {
  db.retrospectives.insertMany(docs, { ordered: false });
}
db.logs.deleteMany({ title: { \$regex: '^k6-ai-review-' } });
printjson({
  student: db.students.findOne({ _id: studentId }, { _id: 1, bojId: 1, role: 1 }),
  retrospectiveCount: db.retrospectives.countDocuments({ studentId: studentId, problemId: { \$regex: '^perf-' } })
});
"
}

seed_fixture_with_mongoimport() {
  require_command mongoimport
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  python3 - "$tmp_dir" "$PERF_BOJ_ID" "$PERF_BCRYPT_PASSWORD" "$PERF_FIXTURE_RETROSPECTIVES" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

tmp_dir, boj_id, password, count = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
now = datetime.now(timezone.utc)

student = {
    "_id": "perf-student-1",
    "nickname": "perfuser",
    "provider": "BOJ",
    "providerId": boj_id,
    "email": "perfuser@example.test",
    "bojId": boj_id,
    "password": password,
    "rating": 0,
    "solvedAcTierLevel": 0,
    "currentTier": "UNRATED",
    "role": "USER",
    "termsAgreed": True,
    "isVerified": True,
    "consecutiveSolveDays": 0,
    "primaryLanguage": "JAVA",
    "isOnboardingFinished": True,
    "createdAt": {"$date": now.isoformat().replace("+00:00", "Z")},
}

retrospectives = []
for i in range(1, count + 1):
    created_at = now - timedelta(hours=i)
    retrospectives.append({
        "_id": f"perf-retro-{i}",
        "studentId": "perf-student-1",
        "problemId": f"perf-{i}",
        "content": f"성능 테스트용 회고 내용입니다. 인덱스와 페이징 조회를 확인합니다. #{i}",
        "summary": f"성능 fixture {i}",
        "createdAt": {"$date": created_at.isoformat().replace("+00:00", "Z")},
        "isBookmarked": i % 10 == 0,
        "solutionResult": "FAIL" if i % 4 == 0 else "SUCCESS",
        "solvedCategory": "DP" if i % 3 == 0 else "Implementation",
        "solveTime": str(300 + i),
    })

with open(f"{tmp_dir}/student.json", "w", encoding="utf-8") as f:
    f.write(json.dumps(student, ensure_ascii=False) + "\n")

with open(f"{tmp_dir}/retrospectives.json", "w", encoding="utf-8") as f:
    for doc in retrospectives:
        f.write(json.dumps(doc, ensure_ascii=False) + "\n")
PY
  mongoimport --quiet --uri "$MONGO_URI" --collection students --mode upsert --upsertFields _id --file "$tmp_dir/student.json"
  mongoimport --quiet --uri "$MONGO_URI" --collection retrospectives --mode upsert --upsertFields _id --file "$tmp_dir/retrospectives.json"
  rm -rf "$tmp_dir"
}

check_k6_version() {
  require_command k6
  local actual
  actual="$(k6 version 2>/dev/null || true)"
  local actual_version
  actual_version="$(awk '{print $2}' <<<"$actual" | head -n 1)"
  if [[ "$actual_version" != "$K6_EXPECTED_VERSION" || "$actual" == *"commit/devel"* ]]; then
    cat >&2 <<VERSION
Expected k6 version: $K6_EXPECTED_VERSION
Actual k6 version: ${actual:-NOT_FOUND}

Install the official Grafana k6 release pinned in performance/k6/K6_VERSION.
For macOS arm64:
  gh release download $K6_EXPECTED_VERSION -R grafana/k6 -p "k6-${K6_EXPECTED_VERSION}-macos-arm64.zip"
  unzip "k6-${K6_EXPECTED_VERSION}-macos-arm64.zip"
  export PATH="\$PWD/k6-${K6_EXPECTED_VERSION}-macos-arm64:\$PATH"
VERSION
    return 1
  fi
}

run_k6() {
  local name="$1"
  local script="$2"
  assert_safe_environment
  validate_number_config
  check_k6_version
  mkdir -p "$RESULTS_DIR"
  SUMMARY_EXPORT="$RESULTS_DIR/$name-$K6_RUN_ID.json" k6 run "$K6_DIR/$script"
}

smoke() {
  run_k6 "smoke" "smoke.js"
}

read_workload() {
  run_k6 "read-workload" "read-workload.js"
}

get_log_id_by_run_id() {
  local run_id="$1"
  if ! command -v mongosh >/dev/null 2>&1; then
    echo "mongosh is required to resolve AI_LOG_ID by runId" >&2
    return 127
  fi
  mongosh "$MONGO_URI" --quiet --eval "const doc = db.logs.findOne({ title: 'k6-ai-review-$run_id' }, { _id: 1 }); if (doc) print(String(doc._id));" | tail -n 1
}

ai_review_once() {
  local iteration="${1:-1}"
  local previous_boj_id="$PERF_BOJ_ID"
  local k6_status=0
  local verify_status=0
  export AI_RUN_ID="${K6_RUN_ID}-ai-${iteration}"
  export PERF_BOJ_ID
  PERF_BOJ_ID="$(ai_boj_id_for "$AI_RUN_ID")"
  configure_wiremock
  local verify_json="$RESULTS_DIR/ai-review-concurrency-$iteration-$AI_RUN_ID-verify.json"
  run_k6 "ai-review-concurrency-$iteration" "ai-review-concurrency.js" || k6_status=$?
  "$VERIFY_AI" \
    --run-id "$AI_RUN_ID" \
    --expect-status COMPLETED \
    --expect-gemini-calls 1 \
    --expect-review-count 1 \
    --poll-timeout-seconds "$AI_COMPLETED_POLL_TIMEOUT_SECONDS" \
    --poll-interval-millis "$AI_POLL_INTERVAL_MILLIS" \
    --output-json "$verify_json" || verify_status=$?
  export PERF_BOJ_ID="$previous_boj_id"
  if [[ "$k6_status" -ne 0 || "$verify_status" -ne 0 ]]; then
    echo "AI concurrency iteration $iteration failed: k6_status=$k6_status verify_status=$verify_status" >&2
    return 1
  fi
  return 0
}

ai_review_repeat() {
  local failed=0
  local pass_count=0
  local fail_count=0
  for iteration in $(seq 1 "$AI_REPEAT_COUNT"); do
    if ! wait_gemini_rate_window; then
      failed=1
      fail_count=$((fail_count + 1))
      echo "AI concurrency iteration $iteration failed before k6: GeminiRateLimiter interval did not open" >&2
      if [[ "$FAIL_FAST_AI_REPEAT" == "true" ]]; then
        break
      fi
      continue
    fi
    if ! ai_review_once "$iteration"; then
      failed=1
      fail_count=$((fail_count + 1))
      echo "AI concurrency iteration $iteration failed" >&2
      if [[ "$FAIL_FAST_AI_REPEAT" == "true" ]]; then
        break
      fi
    else
      pass_count=$((pass_count + 1))
    fi
  done
  python3 - "$RESULTS_DIR/ai-review-repeat-$K6_RUN_ID-aggregate.json" "$AI_REPEAT_COUNT" "$pass_count" "$fail_count" <<'PY'
import json
import sys

path, total, passed, failed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
with open(path, "w", encoding="utf-8") as f:
    json.dump({
        "runId": path.split("ai-review-repeat-", 1)[-1].removesuffix("-aggregate.json"),
        "totalRuns": total,
        "passRuns": passed,
        "failRuns": failed,
        "result": "PASS" if failed == 0 and passed == total else "FAIL",
    }, f, ensure_ascii=False, indent=2)
PY
  return "$failed"
}

wait_gemini_rate_window() {
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli is required to respect GeminiRateLimiter retry interval" >&2
    return 127
  fi
  local deadline=$(( $(date +%s) + AI_FAILED_POLL_TIMEOUT_SECONDS ))
  while true; do
    local last
    last="$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" get "gemini:rate:last:" 2>/dev/null || true)"
    local now
    now="$(date +%s)"
    if [[ -z "$last" || "$last" == "(nil)" || $(( now - last )) -ge 4 ]]; then
      return 0
    fi
    if [[ "$now" -ge "$deadline" ]]; then
      echo "Timed out waiting for GeminiRateLimiter minimum interval" >&2
      return 1
    fi
    sleep 0.25
  done
}

ai_review_failed_retry() {
  export AI_RUN_ID="${K6_RUN_ID}-ai-failed-retry"
  local previous_boj_id="$PERF_BOJ_ID"
  local k6_status=0
  local verify_status=0
  local log_id
  export PERF_BOJ_ID
  PERF_BOJ_ID="$(ai_boj_id_for "$AI_RUN_ID")"
  configure_wiremock

  export AI_EXPERIMENT="failed-first"
  run_k6 "ai-review-failed-retry-first" "ai-review-concurrency.js" || k6_status=$?
  "$VERIFY_AI" \
    --run-id "$AI_RUN_ID" \
    --expect-status FAILED \
    --expect-gemini-calls 1 \
    --expect-review-count 0 \
    --poll-timeout-seconds "$AI_FAILED_POLL_TIMEOUT_SECONDS" \
    --poll-interval-millis "$AI_POLL_INTERVAL_MILLIS" \
    --output-json "$RESULTS_DIR/ai-review-failed-retry-$AI_RUN_ID-failed-verify.json" || verify_status=$?
  log_id="$(get_log_id_by_run_id "$AI_RUN_ID")"
  if [[ -z "$log_id" ]]; then
    echo "Unable to resolve AI failed retry logId for runId=$AI_RUN_ID" >&2
    verify_status=1
  fi

  if [[ -n "$log_id" ]] && ! wait_gemini_rate_window; then
    verify_status=1
  fi

  if [[ -n "$log_id" ]]; then
    export AI_LOG_ID="$log_id"
    export AI_EXPERIMENT="failed-second"
    run_k6 "ai-review-failed-retry-second" "ai-review-concurrency.js" || k6_status=$?
    "$VERIFY_AI" \
      --run-id "$AI_RUN_ID" \
      --expect-status COMPLETED \
      --expect-gemini-calls 2 \
      --expect-review-count 1 \
      --poll-timeout-seconds "$AI_COMPLETED_POLL_TIMEOUT_SECONDS" \
      --poll-interval-millis "$AI_POLL_INTERVAL_MILLIS" \
      --output-json "$RESULTS_DIR/ai-review-failed-retry-$AI_RUN_ID-completed-verify.json" || verify_status=$?

    export AI_EXPERIMENT="failed-final"
    run_k6 "ai-review-failed-retry-final" "ai-review-concurrency.js" || k6_status=$?
  fi

  export PERF_BOJ_ID="$previous_boj_id"
  unset AI_EXPERIMENT AI_LOG_ID
  if [[ "$k6_status" -ne 0 || "$verify_status" -ne 0 ]]; then
    echo "AI failed retry failed: k6_status=$k6_status verify_status=$verify_status" >&2
    return 1
  fi
  return 0
}

auth_rate_limit() {
  local before_cleanup=0
  local k6_status=0
  local after_cleanup=0
  cleanup_rate_limit_keys || before_cleanup=$?
  run_k6 "auth-rate-limit" "auth-rate-limit.js" || k6_status=$?
  cleanup_rate_limit_keys || after_cleanup=$?
  if [[ "$before_cleanup" -ne 0 || "$after_cleanup" -ne 0 ]]; then
    echo "Rate Limit Redis key cleanup failed: before=$before_cleanup after=$after_cleanup" >&2
    return 1
  fi
  return "$k6_status"
}

cleanup_rate_limit_keys() {
  assert_safe_environment
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli not found; cannot cleanup Redis test keys" >&2
    return 127
  fi
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" del \
    "rate_limit:signup:$RATE_LIMIT_SIGNUP_IP" \
    "rate_limit:login:$RATE_LIMIT_LOGIN_IP" \
    "rate_limit:password_reset:$RATE_LIMIT_PASSWORD_RESET_IP" >/dev/null
}

create_jwt() {
  python3 - "$PERF_BOJ_ID" "${JWT_SECRET:-performance-secret-key-must-be-at-least-256-bits-long-1234567890}" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time

subject, secret = sys.argv[1], sys.argv[2]
now = int(time.time())

def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

header = {"alg": "HS256", "typ": "JWT"}
payload = {"sub": subject, "role": "USER", "iat": now, "exp": now + 3600}
unsigned = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}.{b64url(json.dumps(payload, separators=(',', ':')).encode())}"
signature = b64url(hmac.new(secret.encode(), unsigned.encode(), hashlib.sha256).digest())
print(f"{unsigned}.{signature}")
PY
}

preflight() {
  assert_local_fixture_environment
  validate_number_config
  require_command curl
  require_command python3

  local token
  token="$(create_jwt)"
  local auth_header="Authorization: Bearer $token"

  curl -fsS "$BASE_URL/api/v1/system/status" >/dev/null
  curl -fsS -H "$auth_header" "$BASE_URL/api/v1/dashboard" | python3 -c 'import json,sys; body=json.load(sys.stdin); expected=sys.argv[1]; assert body.get("studentProfile", {}).get("bojId") == expected, "dashboard JWT subject does not match fixture BOJ ID"' "$PERF_BOJ_ID" >/dev/null
  curl -fsS -H "$auth_header" "$BASE_URL/api/v1/statistics" >/dev/null
  local list_json
  list_json="$(curl -fsS -H "$auth_header" "$BASE_URL/api/v1/retrospectives?page=1&size=5")"
  local detail_id
  detail_id="$(python3 -c 'import json,sys; body=json.load(sys.stdin); content=body.get("content") or []; assert content, "retrospective fixture list is empty"; print(content[0]["id"])' <<<"$list_json")"
  curl -fsS -H "$auth_header" "$BASE_URL/api/v1/retrospectives/$detail_id" >/dev/null
  wait_for_mock_admin 10

  if ! command -v mongosh >/dev/null 2>&1; then
    echo "mongosh is required for preflight fixture checks" >&2
    return 127
  fi
  mongosh "$MONGO_URI" --quiet --eval "
const student = db.students.findOne({ _id: 'perf-student-1', bojId: '$PERF_BOJ_ID' });
const count = db.retrospectives.countDocuments({ studentId: 'perf-student-1', problemId: { \$regex: '^perf-' } });
if (!student) { throw new Error('performance fixture student not found'); }
if (count !== Number('$PERF_FIXTURE_RETROSPECTIVES')) { throw new Error('retrospective fixture count mismatch: ' + count); }
print(JSON.stringify({ student: student.bojId, retrospectiveCount: count, database: db.getName() }));
" >/dev/null
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping | grep -q PONG
  echo "Preflight passed for BASE_URL=$BASE_URL MONGO_URI=$MONGO_URI REDIS_HOST=$REDIS_HOST WIREMOCK_URL=$WIREMOCK_URL"
}

stop_node_mock() {
  if [[ -f "$NODE_MOCK_PID_FILE" ]]; then
    local pid
    pid="$(cat "$NODE_MOCK_PID_FILE")"
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || return 1
    fi
    rm -f "$NODE_MOCK_PID_FILE"
  fi
}

cleanup() {
  assert_local_fixture_environment
  local status=0
  cleanup_rate_limit_keys || status=1
  stop_node_mock || status=1
  if command -v mongosh >/dev/null 2>&1; then
    mongosh "$MONGO_URI" --quiet --eval "
db.students.deleteMany({ \$or: [
  { _id: 'perf-student-1' },
  { bojId: { \$regex: '^$(safe_boj_token "$PERF_AI_BOJ_ID_PREFIX")' } }
] });
db.retrospectives.deleteMany({ \$or: [
  { _id: { \$regex: '^perf-retro-' } },
  { studentId: 'perf-student-1', problemId: { \$regex: '^perf-' } }
] });
db.logs.deleteMany({ title: { \$regex: '^k6-ai-review-' } });
print(JSON.stringify({ cleanup: 'done', database: db.getName() }));
" >/dev/null || status=1
  else
    echo "mongosh not found; MongoDB fixture cleanup skipped" >&2
    status=1
  fi
  if [[ "${CLEANUP_COMPOSE:-false}" == "true" ]] && command -v docker >/dev/null 2>&1; then
    docker_compose -f "$MOCK_COMPOSE" down || status=1
  fi
  return "$status"
}

all() {
  start_mocks
  seed_fixture
  preflight
  smoke
  read_workload
  ai_review_repeat
  ai_review_failed_retry
  auth_rate_limit
}

case "${1:-help}" in
  start-mocks) start_mocks ;;
  configure-mocks) configure_wiremock ;;
  seed) seed_fixture ;;
  preflight) preflight ;;
  smoke) smoke ;;
  read) read_workload ;;
  ai-review) ai_review_repeat ;;
  ai-review-once) ai_review_once "${2:-1}" ;;
  ai-retry) ai_review_failed_retry ;;
  rate-limit) auth_rate_limit ;;
  cleanup) cleanup ;;
  all) all ;;
  *)
    cat <<USAGE
Usage: performance/k6/run-local.sh <command>

Commands:
  start-mocks       Start local MongoDB, Redis, and WireMock Gemini mock.
  configure-mocks   Reset WireMock journal and configure MOCK_GEMINI_DELAY_MS.
  seed              Seed local MongoDB with a test user and retrospectives.
  preflight         Verify app, JWT, fixtures, Redis, and Gemini mock readiness.
  smoke             Run k6 smoke checks.
  read              Run read workload.
  ai-review         Run AI concurrency test AI_REPEAT_COUNT times (default 10).
  ai-review-once    Run one AI concurrency iteration.
  ai-retry          Run low-load FAILED-state retry verification.
  rate-limit        Run auth Redis Rate Limit policy checks.
  cleanup           Delete only performance fixture data, test Redis keys, and Node mock PID.
  all               Run local services, seed, smoke, read, AI repeat, retry, and rate limit.
USAGE
    ;;
esac
