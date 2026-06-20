#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K6_DIR="$ROOT_DIR/performance/k6"
RESULTS_DIR="$ROOT_DIR/performance/results"
MOCK_COMPOSE="$ROOT_DIR/performance/mock-external/docker-compose.performance.yml"
VERIFY_AI="$ROOT_DIR/performance/verify/verify_ai_call_count.sh"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.performance}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

: "${BASE_URL:=http://localhost:8080}"
: "${WIREMOCK_URL:=http://localhost:8090}"
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
: "${REDIS_HOST:=localhost}"
: "${REDIS_PORT:=6379}"
: "${RATE_LIMIT_SIGNUP_IP:=10.67.1.11}"
: "${RATE_LIMIT_LOGIN_IP:=10.67.1.12}"
: "${RATE_LIMIT_PASSWORD_RESET_IP:=10.67.1.13}"
: "${COMMIT_SHA:=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo NOT_CAPTURED)}"
: "${JAVA_VERSION:=$(java -version 2>&1 | head -n 1 || echo NOT_CAPTURED)}"
: "${KOTLIN_VERSION:=1.9.25}"
: "${K6_RUN_ID:=perf-$(date +%Y%m%d%H%M%S)}"

export BASE_URL WIREMOCK_URL MOCK_GEMINI_DELAY_MS MONGO_URI PERF_BOJ_ID PERF_AI_BOJ_ID_PREFIX
export RATE_LIMIT_SIGNUP_IP RATE_LIMIT_LOGIN_IP RATE_LIMIT_PASSWORD_RESET_IP
export COMMIT_SHA JAVA_VERSION KOTLIN_VERSION K6_RUN_ID
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

safe_boj_token() {
  printf '%s' "$1" | tr -c '[:alnum:]_' '_'
}

ai_boj_id_for() {
  local suffix="$1"
  printf '%s_%s' "$(safe_boj_token "$PERF_AI_BOJ_ID_PREFIX")" "$(safe_boj_token "$suffix")"
}

start_mocks() {
  if command -v docker >/dev/null 2>&1; then
    docker_compose -f "$MOCK_COMPOSE" up -d
  else
    start_local_mongo
    start_local_redis
    start_local_gemini_mock
  fi
  configure_wiremock
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
  if port_open "127.0.0.1" "$port"; then
    return 0
  fi
  require_command node
  mkdir -p /tmp/didimlog-performance
  cat >/tmp/didimlog-performance/gemini-mock.js <<'NODE'
const http = require("http");

const port = Number(process.env.WIREMOCK_PORT || "8090");
let delayMs = Number(process.env.MOCK_GEMINI_DELAY_MS || "500");
let requests = [];
let failedRetryState = "Started";

function readBody(req) {
  return new Promise((resolve) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => resolve(body));
  });
}

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

const success = {
  candidates: [
    {
      content: {
        parts: [
          {
            text: "반복 로직을 함수로 분리하고 입력 범위를 명확히 검증해 유지보수성을 높이세요.",
          },
        ],
      },
    },
  ],
};

const server = http.createServer(async (req, res) => {
  const body = await readBody(req);

  if (req.method === "DELETE" && req.url === "/__admin/requests") {
    requests = [];
    return send(res, 200, { status: "reset" });
  }

  if (req.method === "POST" && req.url === "/__admin/scenarios/reset") {
    failedRetryState = "Started";
    return send(res, 200, { status: "reset" });
  }

  if (req.method === "POST" && req.url === "/__admin/settings") {
    try {
      const parsed = JSON.parse(body || "{}");
      if (typeof parsed.fixedDelay === "number") {
        delayMs = parsed.fixedDelay;
      }
    } catch (_) {
      // Ignore malformed settings and keep current delay.
    }
    return send(res, 200, { fixedDelay: delayMs });
  }

  if (req.method === "POST" && req.url === "/__admin/requests/count") {
    const count = requests.filter((item) => item.method === "POST" && item.url.includes(":generateContent")).length;
    return send(res, 200, { count });
  }

  if (req.method === "POST" && req.url.includes("/v1beta/models/") && req.url.includes(":generateContent")) {
    requests.push({ method: req.method, url: req.url, body });
    setTimeout(() => {
      if (body.includes("FORCE_GEMINI_FAILURE_ONCE") && failedRetryState === "Started") {
        failedRetryState = "failed-once";
        return send(res, 500, {
          error: {
            code: 500,
            message: "forced local Gemini failure for retry verification",
          },
        });
      }
      return send(res, 200, success);
    }, delayMs);
    return;
  }

  send(res, 404, { error: "not found" });
});

server.listen(port, "127.0.0.1", () => {
  console.log(`local Gemini mock listening on ${port}`);
});
NODE
  MOCK_GEMINI_DELAY_MS="$MOCK_GEMINI_DELAY_MS" WIREMOCK_PORT="$port" nohup node /tmp/didimlog-performance/gemini-mock.js >/tmp/didimlog-performance/gemini-mock.log 2>&1 &
  echo $! >/tmp/didimlog-performance/gemini-mock.pid
  for _ in $(seq 1 30); do
    if port_open "127.0.0.1" "$port"; then
      return 0
    fi
    sleep 0.2
  done
  echo "Local Gemini mock did not start on port $port" >&2
  return 1
}

configure_wiremock() {
  require_command curl
  curl -fsS -X POST "$WIREMOCK_URL/__admin/settings" \
    -H "Content-Type: application/json" \
    -d "{\"fixedDelay\":$MOCK_GEMINI_DELAY_MS}" >/dev/null
  curl -fsS -X DELETE "$WIREMOCK_URL/__admin/requests" >/dev/null
  curl -fsS -X POST "$WIREMOCK_URL/__admin/scenarios/reset" >/dev/null
}

seed_fixture() {
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

run_k6() {
  require_command k6
  local name="$1"
  local script="$2"
  mkdir -p "$RESULTS_DIR"
  SUMMARY_EXPORT="$RESULTS_DIR/$name-$K6_RUN_ID.json" k6 run "$K6_DIR/$script"
}

smoke() {
  run_k6 "smoke" "smoke.js"
}

read_workload() {
  run_k6 "read-workload" "read-workload.js"
}

ai_review_once() {
  local iteration="${1:-1}"
  local previous_boj_id="$PERF_BOJ_ID"
  local status=0
  export AI_RUN_ID="${K6_RUN_ID}-ai-${iteration}"
  export PERF_BOJ_ID
  PERF_BOJ_ID="$(ai_boj_id_for "$AI_RUN_ID")"
  configure_wiremock
  if ! run_k6 "ai-review-concurrency-$iteration" "ai-review-concurrency.js"; then
    status=1
  fi
  if [[ "$status" -eq 0 ]] && ! "$VERIFY_AI" --run-id "$AI_RUN_ID"; then
    status=1
  fi
  export PERF_BOJ_ID="$previous_boj_id"
  return "$status"
}

ai_review_repeat() {
  local failed=0
  for iteration in $(seq 1 "$AI_REPEAT_COUNT"); do
    if ! ai_review_once "$iteration"; then
      failed=1
      echo "AI concurrency iteration $iteration failed" >&2
    fi
  done
  return "$failed"
}

ai_review_failed_retry() {
  export AI_EXPERIMENT="failed-retry"
  export AI_RUN_ID="${K6_RUN_ID}-ai-failed-retry"
  local previous_expected="${EXPECTED_GEMINI_CALLS:-}"
  local previous_boj_id="$PERF_BOJ_ID"
  local status=0
  export PERF_BOJ_ID
  PERF_BOJ_ID="$(ai_boj_id_for "$AI_RUN_ID")"
  export EXPECTED_GEMINI_CALLS=2
  configure_wiremock
  if ! run_k6 "ai-review-failed-retry" "ai-review-concurrency.js"; then
    status=1
  fi
  if [[ "$status" -eq 0 ]] && ! "$VERIFY_AI" --run-id "$AI_RUN_ID"; then
    status=1
  fi
  if [[ -n "$previous_expected" ]]; then
    export EXPECTED_GEMINI_CALLS="$previous_expected"
  else
    unset EXPECTED_GEMINI_CALLS
  fi
  export PERF_BOJ_ID="$previous_boj_id"
  unset AI_EXPERIMENT
  return "$status"
}

auth_rate_limit() {
  cleanup_rate_limit_keys
  run_k6 "auth-rate-limit" "auth-rate-limit.js"
  cleanup_rate_limit_keys
}

cleanup_rate_limit_keys() {
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli not found; skipping Redis test key cleanup" >&2
    return 0
  fi
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" del \
    "rate_limit:signup:$RATE_LIMIT_SIGNUP_IP" \
    "rate_limit:login:$RATE_LIMIT_LOGIN_IP" \
    "rate_limit:password_reset:$RATE_LIMIT_PASSWORD_RESET_IP" >/dev/null || true
}

all() {
  start_mocks
  seed_fixture
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
  smoke) smoke ;;
  read) read_workload ;;
  ai-review) ai_review_repeat ;;
  ai-review-once) ai_review_once "${2:-1}" ;;
  ai-retry) ai_review_failed_retry ;;
  rate-limit) auth_rate_limit ;;
  all) all ;;
  *)
    cat <<USAGE
Usage: performance/k6/run-local.sh <command>

Commands:
  start-mocks       Start local MongoDB, Redis, and WireMock Gemini mock.
  configure-mocks   Reset WireMock journal and configure MOCK_GEMINI_DELAY_MS.
  seed              Seed local MongoDB with a test user and retrospectives.
  smoke             Run k6 smoke checks.
  read              Run read workload.
  ai-review         Run AI concurrency test AI_REPEAT_COUNT times (default 10).
  ai-review-once    Run one AI concurrency iteration.
  ai-retry          Run low-load FAILED-state retry verification.
  rate-limit        Run auth Redis Rate Limit policy checks.
  all               Run local services, seed, smoke, read, AI repeat, retry, and rate limit.
USAGE
    ;;
esac
