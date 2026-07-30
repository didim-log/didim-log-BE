#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K6_DIR="$ROOT_DIR/performance/k6"
RESULTS_DIR="$ROOT_DIR/performance/results"
MOCK_COMPOSE="$ROOT_DIR/performance/mock-external/docker-compose.performance.yml"
NODE_MOCK="$ROOT_DIR/performance/mock-external/gemini/node-mock/server.js"
VERIFY_AI="$ROOT_DIR/performance/verify/verify_ai_call_count.sh"
WRITE_ENVIRONMENT_MANIFEST="$ROOT_DIR/performance/verify/write_environment_manifest.sh"
K6_VERSION_FILE="$K6_DIR/K6_VERSION"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.performance}"
NODE_MOCK_PID_FILE="/tmp/didimlog-performance/gemini-mock.pid"

DIDIMLOG_CALLER_K6_RUN_ID_SET="${K6_RUN_ID+x}"
DIDIMLOG_CALLER_K6_RUN_ID="${K6_RUN_ID-}"
DIDIMLOG_CALLER_APPLICATION_WORKTREE_SET="${APPLICATION_WORKTREE+x}"
DIDIMLOG_CALLER_APPLICATION_WORKTREE="${APPLICATION_WORKTREE-}"
DIDIMLOG_CALLER_REPETITION_INDEX_SET="${MEASUREMENT_REPETITION_INDEX+x}"
DIDIMLOG_CALLER_REPETITION_INDEX="${MEASUREMENT_REPETITION_INDEX-}"
DIDIMLOG_CALLER_ALLOW_DIRTY_SET="${ALLOW_DIRTY_PERFORMANCE_RUN+x}"
DIDIMLOG_CALLER_ALLOW_DIRTY="${ALLOW_DIRTY_PERFORMANCE_RUN-}"
DIDIMLOG_CALLER_PROTOCOL_ID_SET="${PERFORMANCE_PROTOCOL_ID+x}"
DIDIMLOG_CALLER_PROTOCOL_ID="${PERFORMANCE_PROTOCOL_ID-}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [[ "$DIDIMLOG_CALLER_K6_RUN_ID_SET" == "x" ]]; then
  K6_RUN_ID="$DIDIMLOG_CALLER_K6_RUN_ID"
fi
if [[ "$DIDIMLOG_CALLER_APPLICATION_WORKTREE_SET" == "x" ]]; then
  APPLICATION_WORKTREE="$DIDIMLOG_CALLER_APPLICATION_WORKTREE"
fi
if [[ "$DIDIMLOG_CALLER_REPETITION_INDEX_SET" == "x" ]]; then
  MEASUREMENT_REPETITION_INDEX="$DIDIMLOG_CALLER_REPETITION_INDEX"
fi
if [[ "$DIDIMLOG_CALLER_ALLOW_DIRTY_SET" == "x" ]]; then
  ALLOW_DIRTY_PERFORMANCE_RUN="$DIDIMLOG_CALLER_ALLOW_DIRTY"
fi
if [[ "$DIDIMLOG_CALLER_PROTOCOL_ID_SET" == "x" ]]; then
  PERFORMANCE_PROTOCOL_ID="$DIDIMLOG_CALLER_PROTOCOL_ID"
fi

: "${BASE_URL:=http://127.0.0.1:8080}"
: "${SERVER_PORT:=8080}"
: "${WIREMOCK_URL:=http://localhost:8090}"
: "${WIREMOCK_PORT:=8090}"
: "${MOCK_GEMINI_MODE:=wiremock}"
: "${MOCK_GEMINI_DELAY_MS:=500}"
: "${MONGO_URI:=mongodb://localhost:27017/didimlog-performance}"
: "${MONGO_HOST:=127.0.0.1}"
: "${MONGO_PORT:=27017}"
: "${REDIS_HOST:=localhost}"
: "${REDIS_PORT:=6379}"
: "${REDIS_DATABASE:=0}"
: "${SPRING_DATA_MONGODB_URI:=$MONGO_URI}"
: "${SPRING_DATA_REDIS_HOST:=$REDIS_HOST}"
: "${SPRING_DATA_REDIS_PORT:=$REDIS_PORT}"
: "${SPRING_DATA_REDIS_DATABASE:=$REDIS_DATABASE}"
: "${SPRING_PROFILES_ACTIVE:=default}"
: "${SPRING_CONFIG_IMPORT:=optional:classpath:/didimlog-performance-no-external-config.properties}"
: "${SPRING_MAIL_HOST:=127.0.0.1}"
: "${SPRING_MAIL_PORT:=1}"
: "${MAIL_PASSWORD:=performance-not-used}"
: "${OAUTH_GOOGLE_ID:=performance-google-id}"
: "${OAUTH_GOOGLE_SECRET:=performance-google-secret}"
: "${OAUTH_GITHUB_ID:=performance-github-id}"
: "${OAUTH_GITHUB_SECRET:=performance-github-secret}"
: "${OAUTH_NAVER_ID:=performance-naver-id}"
: "${OAUTH_NAVER_SECRET:=performance-naver-secret}"
: "${SERVER_URL:=http://127.0.0.1:8080}"
: "${JWT_SECRET:=performance-secret-key-must-be-at-least-256-bits-long-1234567890}"
: "${JWT_ACCESS_TOKEN_EXPIRATION:=1800000}"
: "${JWT_REFRESH_TOKEN_EXPIRATION:=604800000}"
: "${JWT_EXPIRATION:=1800000}"
: "${ADMIN_SECRET_KEY:=performance-admin-secret}"
: "${SWAGGER_USERNAME:=performance-swagger}"
: "${SWAGGER_PASSWORD:=performance-swagger-password}"
: "${AI_ENABLED:=false}"
: "${GEMINI_API_KEY:=local-gemini-key}"
: "${GEMINI_API_URL:=http://localhost:8090/v1beta/models/gemini-2.5-flash:generateContent}"
: "${GEMINI_CONNECT_TIMEOUT_MILLIS:=1000}"
: "${GEMINI_RESPONSE_TIMEOUT_SECONDS:=5}"
: "${GEMINI_READ_TIMEOUT_SECONDS:=5}"
: "${GEMINI_WRITE_TIMEOUT_SECONDS:=5}"
: "${GEMINI_MAX_RETRIES:=0}"
: "${GEMINI_RETRY_BACKOFF_MILLIS:=700}"
: "${AI_REVIEW_ASYNC_CORE_POOL_SIZE:=8}"
: "${AI_REVIEW_ASYNC_MAX_POOL_SIZE:=16}"
: "${AI_REVIEW_ASYNC_QUEUE_CAPACITY:=500}"
: "${PERF_BOJ_ID:=perfuser}"
: "${PERF_STUDENT_ID:=perf-student-1}"
: "${PERF_NICKNAME:=perfuser}"
: "${PERF_AI_BOJ_ID_PREFIX:=${PERF_BOJ_ID}_ai}"
MANIFEST_PERF_BOJ_ID="$PERF_BOJ_ID"
MANIFEST_PERF_STUDENT_ID="$PERF_STUDENT_ID"
: "${PERF_PASSWORD:=PerfPassword123!}"
: "${PERF_BCRYPT_PASSWORD:=\$2y\$10\$FTcPZSUl3qvlezqQQb7oreLZ8T2XID88ICjFjXipc2Ei4EfS7k9SO}"
: "${JWT_TTL_SECONDS:=3600}"
: "${FIXTURE_VERSION:=be-refactor-phase0b-v1}"
: "${PERF_FIXTURE_EPOCH:=2026-07-26T00:00:00Z}"
: "${PERF_FIXTURE_RETROSPECTIVES:=100}"
: "${MEASUREMENT_REPETITION_INDEX:=1}"
: "${MEASUREMENT_REPETITION_TOTAL:=5}"
: "${PERFORMANCE_PROTOCOL_ID:=be-refactor-phase0b-v1}"
: "${ALLOW_DIRTY_PERFORMANCE_RUN:=false}"
: "${AI_REPEAT_COUNT:=10}"
: "${AI_CONCURRENCY:=50}"
: "${AI_POLL_TIMEOUT_SECONDS:=30}"
: "${AI_POLL_INTERVAL_MILLIS:=250}"
: "${AI_FAILED_POLL_TIMEOUT_SECONDS:=20}"
: "${AI_COMPLETED_POLL_TIMEOUT_SECONDS:=30}"
: "${AI_SYNC_WAIT_MS:=3000}"
: "${AI_MAX_DURATION:=45s}"
: "${EXPECTED_GEMINI_CALLS:=1}"
: "${FAIL_FAST_AI_REPEAT:=false}"
: "${P95_MS:=}"
: "${RATE_LIMIT_CLIENT_IP:=127.0.0.1}"
: "${READ_RETROSPECTIVE_IDS:=}"
: "${TARGET_ENVIRONMENT:=local}"
: "${ALLOW_REMOTE_LOAD_TEST:=false}"
: "${REMOTE_TARGET_ALLOWLIST:=}"
: "${COMMIT_SHA:=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo NOT_CAPTURED)}"
: "${APPLICATION_BASELINE_SHA:=74f7941d8d28275b9abe38877f32c4216955350b}"
: "${APPLICATION_WORKTREE:=$ROOT_DIR}"
if [[ "$APPLICATION_WORKTREE" != /* ]]; then
  APPLICATION_WORKTREE="$ROOT_DIR/$APPLICATION_WORKTREE"
fi
APPLICATION_WORKTREE="$(cd "$APPLICATION_WORKTREE" && pwd)"
APPLICATION_COMMIT_SHA="$(git -C "$APPLICATION_WORKTREE" rev-parse HEAD 2>/dev/null || echo NOT_CAPTURED)"
APPLICATION_GIT_DIRTY="$([[ -n "$(git -C "$APPLICATION_WORKTREE" status --porcelain 2>/dev/null)" ]] && echo true || echo false)"
: "${JAVA_VERSION:=$(java -version 2>&1 | awk '/^(openjdk|java) version "/ { print; found=1; exit } END { if (!found) print "NOT_CAPTURED" }')}"
: "${KOTLIN_VERSION:=1.9.25}"
: "${K6_RUN_ID:=perf-$(date +%Y%m%d%H%M%S)}"
: "${APP_RUNTIME_MODE:=gradle-toolchain}"
: "${APP_JAVA_VERSION:=17}"
: "${APP_JVM_XMS:=512m}"
: "${APP_JVM_XMX:=512m}"
: "${APP_JVM_GC:=G1}"
: "${APP_TIMEZONE:=Asia/Seoul}"
: "${JVM_HEAP:=-Xms$APP_JVM_XMS -Xmx$APP_JVM_XMX}"
: "${CPU_INFO:=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | head -n 1 || echo NOT_CAPTURED)}"
: "${MEMORY_INFO:=$(sysctl -n hw.memsize 2>/dev/null || grep MemTotal /proc/meminfo 2>/dev/null || echo NOT_CAPTURED)}"

K6_EXPECTED_VERSION="$(tr -d '[:space:]' <"$K6_VERSION_FILE")"
GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR" status --porcelain 2>/dev/null)" ]] && echo true || echo false)"

export BASE_URL SERVER_PORT WIREMOCK_URL WIREMOCK_PORT MOCK_GEMINI_DELAY_MS
export MONGO_URI MONGO_PORT PERF_BOJ_ID PERF_STUDENT_ID PERF_NICKNAME PERF_AI_BOJ_ID_PREFIX PERF_PASSWORD PERF_BCRYPT_PASSWORD
export JWT_TTL_SECONDS
export REDIS_HOST REDIS_PORT REDIS_DATABASE
export SPRING_DATA_MONGODB_URI SPRING_DATA_REDIS_HOST SPRING_DATA_REDIS_PORT SPRING_DATA_REDIS_DATABASE
export SPRING_PROFILES_ACTIVE SPRING_CONFIG_IMPORT
export SPRING_MAIL_HOST SPRING_MAIL_PORT MAIL_PASSWORD
export OAUTH_GOOGLE_ID OAUTH_GOOGLE_SECRET OAUTH_GITHUB_ID OAUTH_GITHUB_SECRET
export OAUTH_NAVER_ID OAUTH_NAVER_SECRET SERVER_URL JWT_SECRET
export JWT_ACCESS_TOKEN_EXPIRATION JWT_REFRESH_TOKEN_EXPIRATION JWT_EXPIRATION
export ADMIN_SECRET_KEY SWAGGER_USERNAME SWAGGER_PASSWORD AI_ENABLED GEMINI_API_KEY
export GEMINI_API_URL GEMINI_CONNECT_TIMEOUT_MILLIS GEMINI_RESPONSE_TIMEOUT_SECONDS
export GEMINI_READ_TIMEOUT_SECONDS GEMINI_WRITE_TIMEOUT_SECONDS GEMINI_MAX_RETRIES
export GEMINI_RETRY_BACKOFF_MILLIS
export AI_REVIEW_ASYNC_CORE_POOL_SIZE AI_REVIEW_ASYNC_MAX_POOL_SIZE AI_REVIEW_ASYNC_QUEUE_CAPACITY
export RATE_LIMIT_CLIENT_IP P95_MS READ_RETROSPECTIVE_IDS
export COMMIT_SHA JAVA_VERSION KOTLIN_VERSION K6_RUN_ID JVM_HEAP CPU_INFO MEMORY_INFO
export APPLICATION_BASELINE_SHA APPLICATION_WORKTREE APPLICATION_COMMIT_SHA APPLICATION_GIT_DIRTY
export APP_RUNTIME_MODE APP_JAVA_VERSION APP_JVM_XMS APP_JVM_XMX APP_JVM_GC APP_TIMEZONE
export FIXTURE_VERSION PERF_FIXTURE_EPOCH PERF_FIXTURE_RETROSPECTIVES
export MEASUREMENT_REPETITION_INDEX MEASUREMENT_REPETITION_TOTAL PERFORMANCE_PROTOCOL_ID
export ALLOW_DIRTY_PERFORMANCE_RUN
export AI_CONCURRENCY AI_REPEAT_COUNT AI_POLL_TIMEOUT_SECONDS AI_POLL_INTERVAL_MILLIS
export AI_FAILED_POLL_TIMEOUT_SECONDS AI_COMPLETED_POLL_TIMEOUT_SECONDS
export AI_SYNC_WAIT_MS AI_MAX_DURATION EXPECTED_GEMINI_CALLS FAIL_FAST_AI_REPEAT
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
    docker compose --project-name didimlog-performance "$@"
    return
  fi
  docker-compose --project-name didimlog-performance "$@"
}

reset_performance_volumes() {
  assert_local_fixture_environment || return $?
  require_command docker
  if [[ "$MOCK_GEMINI_MODE" != "wiremock" ]]; then
    echo "Performance volume reset requires MOCK_GEMINI_MODE=wiremock." >&2
    return 2
  fi
  if [[ "${CONFIRM_PERFORMANCE_VOLUME_RESET:-}" != "didimlog-performance" ]]; then
    cat >&2 <<CONFIRM
Refusing to reset volumes without explicit confirmation.
Run:
  CONFIRM_PERFORMANCE_VOLUME_RESET=didimlog-performance \
    performance/k6/run-local.sh reset-volumes
CONFIRM
    return 2
  fi

  local configured_volumes
  configured_volumes="$(
    docker_compose -f "$MOCK_COMPOSE" config --volumes |
      sed '/^[[:space:]]*$/d' |
      LC_ALL=C sort
  )"
  if [[ "$configured_volumes" != "didimlog-performance-mongo-data" ]]; then
    echo "Unexpected performance compose volumes; refusing reset:" >&2
    printf '%s\n' "$configured_volumes" >&2
    return 2
  fi

  docker_compose -f "$MOCK_COMPOSE" down --volumes
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
  assert_safe_environment || return $?
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
  python3 - \
    "$AI_CONCURRENCY" \
    "$AI_REPEAT_COUNT" \
    "$MOCK_GEMINI_DELAY_MS" \
    "${READ_VUS:-10}" \
    "${RATE_LIMIT_OVERAGE_REQUESTS:-2}" \
    "${READ_PAGE_SIZE:-10}" \
    "$PERF_FIXTURE_EPOCH" \
    "$FIXTURE_VERSION" \
    "$MEASUREMENT_REPETITION_INDEX" \
    "$MEASUREMENT_REPETITION_TOTAL" \
    "$K6_RUN_ID" <<'PY'
from datetime import datetime
import re
import sys

checks = [
    ("AI_CONCURRENCY", sys.argv[1], 1, 500),
    ("AI_REPEAT_COUNT", sys.argv[2], 1, 100),
    ("MOCK_GEMINI_DELAY_MS", sys.argv[3], 0, 30000),
    ("READ_VUS", sys.argv[4], 1, 500),
    ("RATE_LIMIT_OVERAGE_REQUESTS", sys.argv[5], 1, 20),
    ("READ_PAGE_SIZE", sys.argv[6], 1, 100),
    ("MEASUREMENT_REPETITION_INDEX", sys.argv[9], 1, 100),
    ("MEASUREMENT_REPETITION_TOTAL", sys.argv[10], 1, 100),
]
for name, value, low, high in checks:
    if not re.fullmatch(r"0|[1-9]\d*", str(value)):
        raise SystemExit(f"{name} must be an integer: {value}")
    parsed = int(value)
    if parsed < low or parsed > high:
        raise SystemExit(f"{name} must be between {low} and {high}: {value}")

if int(sys.argv[9]) > int(sys.argv[10]):
    raise SystemExit("MEASUREMENT_REPETITION_INDEX cannot exceed MEASUREMENT_REPETITION_TOTAL")

try:
    epoch = datetime.fromisoformat(sys.argv[7].replace("Z", "+00:00"))
except ValueError as error:
    raise SystemExit(f"PERF_FIXTURE_EPOCH must be ISO-8601: {sys.argv[7]}") from error
if epoch.tzinfo is None:
    raise SystemExit("PERF_FIXTURE_EPOCH must include a timezone")

if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", sys.argv[8]):
    raise SystemExit(f"FIXTURE_VERSION must be a lowercase version identifier: {sys.argv[8]}")

if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", sys.argv[11]):
    raise SystemExit("K6_RUN_ID must be 1-81 characters using letters, digits, dot, underscore, or hyphen")
PY
}

safe_boj_token() {
  printf '%s' "$1" | tr -c '[:alnum:]_' '_'
}

ai_boj_id_for() {
  local suffix="$1"
  printf '%s_%s' "$(safe_boj_token "$PERF_AI_BOJ_ID_PREFIX")" "$(safe_boj_token "$suffix")"
}

ai_student_id_for() {
  local suffix="$1"
  printf 'perf-student-ai-%s' "$(safe_boj_token "$suffix")"
}

ai_nickname_for() {
  python3 - "$1" <<'PY'
import hashlib
import sys

print("ai" + hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest()[:10])
PY
}

start_mocks() {
  assert_local_fixture_environment || return $?
  validate_number_config || return $?
  case "$MOCK_GEMINI_MODE" in
    auto)
      if command -v docker >/dev/null 2>&1; then
        if docker_compose -f "$MOCK_COMPOSE" up -d; then
          if wait_for_mock_admin 30; then
            configure_wiremock || return $?
            return 0
          fi
          echo "WireMock health check failed in auto mode; falling back to Node Gemini mock." >&2
          stop_wiremock_container || true
          start_local_gemini_mock || return $?
          configure_wiremock || return $?
          return 0
        fi
        echo "Docker Compose startup failed in auto mode; falling back to local services." >&2
      fi
      start_local_mongo || return $?
      start_local_redis || return $?
      start_local_gemini_mock || return $?
      configure_wiremock
      ;;
    wiremock)
      require_command docker
      docker_compose -f "$MOCK_COMPOSE" up -d || return $?
      wait_for_mock_admin 30 || return $?
      configure_wiremock
      ;;
    node)
      start_local_mongo || return $?
      start_local_redis || return $?
      start_local_gemini_mock || return $?
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
  assert_local_fixture_environment || return $?
  require_command curl
  curl -fsS -X POST "$WIREMOCK_URL/__admin/settings" \
    -H "Content-Type: application/json" \
    -d "{\"fixedDelay\":$MOCK_GEMINI_DELAY_MS}" >/dev/null || return $?
  curl -fsS -X DELETE "$WIREMOCK_URL/__admin/requests" >/dev/null || return $?
  curl -fsS -X POST "$WIREMOCK_URL/__admin/scenarios/reset" >/dev/null
}

seed_fixture() {
  assert_local_fixture_environment || return $?
  validate_number_config || return $?
  if command -v mongosh >/dev/null 2>&1; then
    seed_fixture_with_mongosh
    return
  fi
  seed_fixture_with_mongoimport
}

seed_fixture_with_mongosh() {
  local include_related="${1:-true}"
  mongosh "$MONGO_URI" --quiet --eval "
const studentId = '$PERF_STUDENT_ID';
const bojId = '$PERF_BOJ_ID';
const nickname = '$PERF_NICKNAME';
const includeRelated = $include_related;
const now = new Date('$PERF_FIXTURE_EPOCH');
if (Number.isNaN(now.getTime())) {
  throw new Error('invalid PERF_FIXTURE_EPOCH');
}
db.students.updateOne(
  { _id: studentId },
  {
    \$set: {
      nickname: nickname,
      provider: 'BOJ',
      providerId: bojId,
      email: bojId + '@example.test',
      bojId: bojId,
      password: '$PERF_BCRYPT_PASSWORD',
      credentialVersion: 0,
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

let retrospectiveCount = 0;
if (includeRelated) {
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
  retrospectiveCount = db.retrospectives.countDocuments({
    studentId: studentId,
    problemId: { \$regex: '^perf-' }
  });
}
printjson({
  student: db.students.findOne({ _id: studentId }, { _id: 1, bojId: 1, role: 1 }),
  retrospectiveCount: retrospectiveCount
});
"
}

seed_fixture_with_mongoimport() {
  local include_related="${1:-true}"
  require_command mongoimport
  local tmp_dir
  local retrospective_count=0
  if [[ "$include_related" == "true" ]]; then
    retrospective_count="$PERF_FIXTURE_RETROSPECTIVES"
  fi
  tmp_dir="$(mktemp -d)"
  python3 - \
    "$tmp_dir" \
    "$PERF_STUDENT_ID" \
    "$PERF_BOJ_ID" \
    "$PERF_NICKNAME" \
    "$PERF_BCRYPT_PASSWORD" \
    "$retrospective_count" \
    "$PERF_FIXTURE_EPOCH" <<'PY'
import json
import sys
from datetime import datetime, timedelta

tmp_dir, student_id, boj_id, nickname, password, count, fixture_epoch = (
    sys.argv[1],
    sys.argv[2],
    sys.argv[3],
    sys.argv[4],
    sys.argv[5],
    int(sys.argv[6]),
    sys.argv[7],
)
now = datetime.fromisoformat(fixture_epoch.replace("Z", "+00:00"))

student = {
    "_id": student_id,
    "nickname": nickname,
    "provider": "BOJ",
    "providerId": boj_id,
    "email": f"{boj_id}@example.test",
    "bojId": boj_id,
    "password": password,
    "credentialVersion": 0,
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
        "studentId": student_id,
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
  if [[ "$include_related" == "true" ]]; then
    mongoimport --quiet --uri "$MONGO_URI" --collection retrospectives --mode upsert --upsertFields _id --file "$tmp_dir/retrospectives.json"
  fi
  rm -rf "$tmp_dir"
}

seed_ai_student_fixture() {
  assert_local_fixture_environment || return $?

  if command -v mongosh >/dev/null 2>&1; then
    seed_fixture_with_mongosh false >/dev/null
    return
  fi

  seed_fixture_with_mongoimport false
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

environment_manifest_path() {
  printf '%s/environment-%s.json' "$RESULTS_DIR" "$K6_RUN_ID"
}

write_environment_manifest() {
  assert_local_fixture_environment || return $?
  validate_number_config || return $?
  require_command python3
  local output
  output="$(environment_manifest_path)"
  "$WRITE_ENVIRONMENT_MANIFEST" --output "$output"
}

ensure_environment_manifest() {
  write_environment_manifest
}

start_application() {
  assert_local_fixture_environment || return $?
  validate_number_config || return $?
  if [[ "$APP_RUNTIME_MODE" != "gradle-toolchain" ]]; then
    echo "start-app requires APP_RUNTIME_MODE=gradle-toolchain." >&2
    return 2
  fi

  local expected_java_tool_options
  expected_java_tool_options="-Xms$APP_JVM_XMS -Xmx$APP_JVM_XMX -XX:+Use${APP_JVM_GC}GC"
  if [[ "${JAVA_TOOL_OPTIONS:-}" != "$expected_java_tool_options" ]]; then
    echo "JAVA_TOOL_OPTIONS must exactly match: $expected_java_tool_options" >&2
    return 2
  fi
  if [[ -n "${_JAVA_OPTIONS:-}" || -n "${JDK_JAVA_OPTIONS:-}" ]]; then
    echo "_JAVA_OPTIONS and JDK_JAVA_OPTIONS must be unset for start-app." >&2
    return 2
  fi
  if [[ -n "${SPRING_APPLICATION_JSON:-}" ]]; then
    echo "SPRING_APPLICATION_JSON must be unset for start-app." >&2
    return 2
  fi
  if [[ ! -x "$APPLICATION_WORKTREE/gradlew" ]]; then
    echo "Gradle wrapper is not executable: $APPLICATION_WORKTREE/gradlew" >&2
    return 2
  fi
  if [[ -z "${HOME:-}" || -z "${PATH:-}" ]]; then
    echo "HOME and PATH are required to start the application." >&2
    return 2
  fi

  write_environment_manifest || return $?
  local didimlog_gradle_home="/tmp/didimlog-performance/gradle-user-home"
  mkdir -p "$didimlog_gradle_home"
  local -a didimlog_application_env=(
    env -i
    "HOME=$HOME"
    "PATH=$PATH"
    "LANG=${LANG:-C}"
    "TMPDIR=${TMPDIR:-/tmp}"
    "GRADLE_USER_HOME=$didimlog_gradle_home"
    "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
    "TZ=$APP_TIMEZONE"
    "SERVER_PORT=$SERVER_PORT"
    "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"
    "SPRING_CONFIG_IMPORT=$SPRING_CONFIG_IMPORT"
    "SPRING_DATA_MONGODB_URI=$SPRING_DATA_MONGODB_URI"
    "SPRING_DATA_REDIS_HOST=$SPRING_DATA_REDIS_HOST"
    "SPRING_DATA_REDIS_PORT=$SPRING_DATA_REDIS_PORT"
    "SPRING_DATA_REDIS_DATABASE=$SPRING_DATA_REDIS_DATABASE"
    "SPRING_MAIL_HOST=$SPRING_MAIL_HOST"
    "SPRING_MAIL_PORT=$SPRING_MAIL_PORT"
    "MAIL_PASSWORD=$MAIL_PASSWORD"
    "OAUTH_GOOGLE_ID=$OAUTH_GOOGLE_ID"
    "OAUTH_GOOGLE_SECRET=$OAUTH_GOOGLE_SECRET"
    "OAUTH_GITHUB_ID=$OAUTH_GITHUB_ID"
    "OAUTH_GITHUB_SECRET=$OAUTH_GITHUB_SECRET"
    "OAUTH_NAVER_ID=$OAUTH_NAVER_ID"
    "OAUTH_NAVER_SECRET=$OAUTH_NAVER_SECRET"
    "SERVER_URL=$SERVER_URL"
    "JWT_SECRET=$JWT_SECRET"
    "JWT_ACCESS_TOKEN_EXPIRATION=$JWT_ACCESS_TOKEN_EXPIRATION"
    "JWT_REFRESH_TOKEN_EXPIRATION=$JWT_REFRESH_TOKEN_EXPIRATION"
    "JWT_EXPIRATION=$JWT_EXPIRATION"
    "ADMIN_SECRET_KEY=$ADMIN_SECRET_KEY"
    "SWAGGER_USERNAME=$SWAGGER_USERNAME"
    "SWAGGER_PASSWORD=$SWAGGER_PASSWORD"
    "AI_ENABLED=$AI_ENABLED"
    "GEMINI_API_KEY=$GEMINI_API_KEY"
    "GEMINI_API_URL=$GEMINI_API_URL"
    "GEMINI_CONNECT_TIMEOUT_MILLIS=$GEMINI_CONNECT_TIMEOUT_MILLIS"
    "GEMINI_RESPONSE_TIMEOUT_SECONDS=$GEMINI_RESPONSE_TIMEOUT_SECONDS"
    "GEMINI_READ_TIMEOUT_SECONDS=$GEMINI_READ_TIMEOUT_SECONDS"
    "GEMINI_WRITE_TIMEOUT_SECONDS=$GEMINI_WRITE_TIMEOUT_SECONDS"
    "GEMINI_MAX_RETRIES=$GEMINI_MAX_RETRIES"
    "GEMINI_RETRY_BACKOFF_MILLIS=$GEMINI_RETRY_BACKOFF_MILLIS"
    "AI_REVIEW_ASYNC_CORE_POOL_SIZE=$AI_REVIEW_ASYNC_CORE_POOL_SIZE"
    "AI_REVIEW_ASYNC_MAX_POOL_SIZE=$AI_REVIEW_ASYNC_MAX_POOL_SIZE"
    "AI_REVIEW_ASYNC_QUEUE_CAPACITY=$AI_REVIEW_ASYNC_QUEUE_CAPACITY"
  )
  if [[ -n "${JAVA_HOME:-}" ]]; then
    didimlog_application_env+=("JAVA_HOME=$JAVA_HOME")
  fi

  cd "$APPLICATION_WORKTREE"
  exec "${didimlog_application_env[@]}" ./gradlew bootRun --no-daemon
}

run_k6() {
  local name="$1"
  local script="$2"
  local workload_boj_id="$PERF_BOJ_ID"
  local workload_student_id="$PERF_STUDENT_ID"
  assert_safe_environment || return $?
  validate_number_config || return $?
  check_k6_version || return $?
  mkdir -p "$RESULTS_DIR"
  PERF_BOJ_ID="$MANIFEST_PERF_BOJ_ID" \
    PERF_STUDENT_ID="$MANIFEST_PERF_STUDENT_ID" \
    ensure_environment_manifest || return $?
  PERF_BOJ_ID="$workload_boj_id" \
    PERF_STUDENT_ID="$workload_student_id" \
    SUMMARY_EXPORT="$RESULTS_DIR/$name-$K6_RUN_ID.json" \
    k6 run "$K6_DIR/$script"
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
  local ai_boj_id
  local ai_student_id
  local ai_nickname
  local k6_status=0
  local verify_status=0
  export AI_RUN_ID="${K6_RUN_ID}-ai-${iteration}"
  ai_boj_id="$(ai_boj_id_for "$AI_RUN_ID")"
  ai_student_id="$(ai_student_id_for "$AI_RUN_ID")"
  ai_nickname="$(ai_nickname_for "$AI_RUN_ID")"
  configure_wiremock || return $?
  if ! PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
    PERF_NICKNAME="$ai_nickname" seed_ai_student_fixture; then
    echo "AI fixture seed failed: studentId=$ai_student_id bojId=$ai_boj_id" >&2
    return 1
  fi
  local verify_json="$RESULTS_DIR/ai-review-concurrency-$iteration-$AI_RUN_ID-verify.json"
  PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
    run_k6 "ai-review-concurrency-$iteration" "ai-review-concurrency.js" || k6_status=$?
  "$VERIFY_AI" \
    --run-id "$AI_RUN_ID" \
    --expect-status COMPLETED \
    --expect-gemini-calls 1 \
    --expect-review-count 1 \
    --poll-timeout-seconds "$AI_COMPLETED_POLL_TIMEOUT_SECONDS" \
    --poll-interval-millis "$AI_POLL_INTERVAL_MILLIS" \
    --output-json "$verify_json" || verify_status=$?
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
  assert_safe_environment || return $?
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli is required to respect GeminiRateLimiter retry interval" >&2
    return 127
  fi
  local deadline=$(( $(date +%s) + AI_FAILED_POLL_TIMEOUT_SECONDS ))
  while true; do
    local last
    last="$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DATABASE" get "gemini:rate:last:" 2>/dev/null || true)"
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
  local ai_boj_id
  local ai_student_id
  local ai_nickname
  local k6_status=0
  local verify_status=0
  local log_id
  ai_boj_id="$(ai_boj_id_for "$AI_RUN_ID")"
  ai_student_id="$(ai_student_id_for "$AI_RUN_ID")"
  ai_nickname="$(ai_nickname_for "$AI_RUN_ID")"
  configure_wiremock || return $?
  if ! PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
    PERF_NICKNAME="$ai_nickname" seed_ai_student_fixture; then
    echo "AI retry fixture seed failed: studentId=$ai_student_id bojId=$ai_boj_id" >&2
    return 1
  fi

  export AI_EXPERIMENT="failed-first"
  PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
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
    PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
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
    PERF_BOJ_ID="$ai_boj_id" PERF_STUDENT_ID="$ai_student_id" \
      run_k6 "ai-review-failed-retry-final" "ai-review-concurrency.js" || k6_status=$?
  fi

  unset AI_EXPERIMENT AI_LOG_ID
  if [[ "$k6_status" -ne 0 || "$verify_status" -ne 0 ]]; then
    echo "AI failed retry failed: k6_status=$k6_status verify_status=$verify_status" >&2
    return 1
  fi
  return 0
}

auth_rate_limit() {
  local k6_status=0
  local after_cleanup=0
  if ! cleanup_rate_limit_keys; then
    echo "Rate Limit Redis key cleanup failed before execution" >&2
    return 1
  fi
  run_k6 "auth-rate-limit" "auth-rate-limit.js" || k6_status=$?
  cleanup_rate_limit_keys || after_cleanup=$?
  if [[ "$after_cleanup" -ne 0 ]]; then
    echo "Rate Limit Redis key cleanup failed after execution: status=$after_cleanup" >&2
    return 1
  fi
  return "$k6_status"
}

cleanup_rate_limit_keys() {
  assert_safe_environment || return $?
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli not found; cannot cleanup Redis test keys" >&2
    return 127
  fi
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DATABASE" del \
    "rate_limit:signup:$RATE_LIMIT_CLIENT_IP" \
    "rate_limit:login:$RATE_LIMIT_CLIENT_IP" \
    "rate_limit:password_reset:$RATE_LIMIT_CLIENT_IP" >/dev/null
}

cleanup_ai_fixture_keys() {
  assert_local_fixture_environment || return $?
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli not found; cannot cleanup AI fixture Redis keys" >&2
    return 127
  fi

  local today
  local global_usage_key
  local iteration
  local run_id
  local student_id
  local key
  local usage_keys=()
  local cache_keys=()
  today="$(TZ="$APP_TIMEZONE" date +%F)"
  global_usage_key="AI_USAGE:GLOBAL:$today"
  usage_keys+=("AI_USAGE:USER:$PERF_STUDENT_ID:$today")

  for iteration in $(seq 1 "$AI_REPEAT_COUNT"); do
    run_id="${K6_RUN_ID}-ai-${iteration}"
    student_id="$(ai_student_id_for "$run_id")"
    usage_keys+=("AI_USAGE:USER:$student_id:$today")
  done
  run_id="${K6_RUN_ID}-ai-failed-retry"
  student_id="$(ai_student_id_for "$run_id")"
  usage_keys+=("AI_USAGE:USER:$student_id:$today")

  while IFS= read -r key; do
    if [[ -n "$key" ]]; then
      cache_keys+=("$key")
    fi
  done < <(python3 - "$K6_RUN_ID" "$AI_REPEAT_COUNT" <<'PY'
import hashlib
import sys

run_id, repeat_count = sys.argv[1], int(sys.argv[2])

def cache_key(code, result):
    normalized = code.strip()[:2000]
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return f"AI_REVIEW:CACHE:v1:{result}:{digest}"

for iteration in range(1, repeat_count + 1):
    ai_run_id = f"{run_id}-ai-{iteration}"
    code = "\n".join([
        f"// k6 unique AI fixture: {ai_run_id}",
        "public class Main {",
        "  public static void main(String[] args) {",
        "    int sum = 0;",
        "    for (int i = 0; i < 100; i++) { sum += i; }",
        "    System.out.println(sum);",
        "  }",
        "}",
    ])
    print(cache_key(code, "success"))

retry_run_id = f"{run_id}-ai-failed-retry"
retry_code = "\n".join([
    f"// k6 unique failed-retry fixture: {retry_run_id}",
    "public class Main {",
    '  static final String MARKER = "FORCE_GEMINI_FAILURE_ONCE";',
    "  public static void main(String[] args) {",
    "    System.out.println(MARKER.length());",
    "  }",
    "}",
])
print(cache_key(retry_code, "fail"))
PY
)

  if ! redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DATABASE" eval \
    "local removed = 0
     for _, userKey in ipairs(ARGV) do
       local value = tonumber(redis.call('GET', userKey) or '0') or 0
       removed = removed + value
       redis.call('DEL', userKey)
     end
     local global = tonumber(redis.call('GET', KEYS[1]) or '0') or 0
     if removed > 0 and global > 0 then
       if global > removed then
         redis.call('DECRBY', KEYS[1], removed)
       else
         redis.call('DEL', KEYS[1])
       end
     end
     return removed" \
    1 "$global_usage_key" "${usage_keys[@]}" >/dev/null; then
    echo "AI usage fixture Redis key cleanup failed" >&2
    return 1
  fi

  if [[ "${#cache_keys[@]}" -gt 0 ]]; then
    if ! redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DATABASE" del \
      "${cache_keys[@]}" >/dev/null; then
      echo "AI review cache fixture Redis key cleanup failed" >&2
      return 1
    fi
  fi
}

create_jwt() {
  python3 - \
    "$PERF_BOJ_ID" \
    "$PERF_STUDENT_ID" \
    "${JWT_SECRET:-performance-secret-key-must-be-at-least-256-bits-long-1234567890}" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time

subject, student_id, secret = sys.argv[1], sys.argv[2], sys.argv[3]
now = int(time.time())

def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

header = {"alg": "HS256", "typ": "JWT"}
payload = {
    "sub": subject,
    "role": "USER",
    "type": "access",
    "studentId": student_id,
    "credentialVersion": 0,
    "iat": now,
    "exp": now + 3600,
}
unsigned = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}.{b64url(json.dumps(payload, separators=(',', ':')).encode())}"
signature = b64url(hmac.new(secret.encode(), unsigned.encode(), hashlib.sha256).digest())
print(f"{unsigned}.{signature}")
PY
}

preflight() {
  assert_local_fixture_environment || return $?
  validate_number_config || return $?
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
const student = db.students.findOne({
  _id: '$PERF_STUDENT_ID',
  bojId: '$PERF_BOJ_ID',
  credentialVersion: 0
});
const count = db.retrospectives.countDocuments({ studentId: '$PERF_STUDENT_ID', problemId: { \$regex: '^perf-' } });
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
  assert_local_fixture_environment || return $?
  local status=0
  cleanup_rate_limit_keys || status=1
  cleanup_ai_fixture_keys || status=1
  stop_node_mock || status=1
  if command -v mongosh >/dev/null 2>&1; then
    mongosh "$MONGO_URI" --quiet --eval "
db.students.deleteMany({ \$or: [
  { _id: '$PERF_STUDENT_ID' },
  { bojId: { \$regex: '^$(safe_boj_token "$PERF_AI_BOJ_ID_PREFIX")' } }
] });
db.retrospectives.deleteMany({ \$or: [
  { _id: { \$regex: '^perf-retro-' } },
  { studentId: '$PERF_STUDENT_ID', problemId: { \$regex: '^perf-' } }
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
  start-app) start_application ;;
  configure-mocks) configure_wiremock ;;
  environment-manifest) write_environment_manifest ;;
  reset-volumes) reset_performance_volumes ;;
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
  start-app         Start the configured application worktree with the fixed JVM settings.
  configure-mocks   Reset WireMock journal and configure MOCK_GEMINI_DELAY_MS.
  environment-manifest
                    Write the allowlist-only environment manifest for K6_RUN_ID.
  reset-volumes     Reset only the performance compose volume after explicit confirmation.
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
