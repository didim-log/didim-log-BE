#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_ROOT="$ROOT_DIR/performance/results"
RUN_ID="${CRAWLER_BASELINE_RUN_ID:-crawler-baseline-$(date +%Y%m%d%H%M%S)-$$}"
OUTPUT_DIR="$RESULTS_ROOT/$RUN_ID"
REPEATS="${CRAWLER_BASELINE_REPEATS:-5}"
EXPECTED_FIND_COUNT="${CRAWLER_BASELINE_EXPECTED_FIND_COUNT:-6}"

MONGO_IMAGE="mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a"
REDIS_IMAGE="redis:7.2.5-alpine@sha256:6aaf3f5e6bc8a592fbfe2cccf19eb36d27c39d12dab4f4b01556b7449e7b1f44"
MONGO_PORT="${CRAWLER_MONGO_PORT:-27117}"
REDIS_PORT="${CRAWLER_REDIS_PORT:-6389}"
MONGO_CONTAINER="${RUN_ID}-mongo"
REDIS_CONTAINER="${RUN_ID}-redis"

MONGO_STARTED=false
REDIS_STARTED=false

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

validate_port() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < 1024 || value > 65535 )); then
    echo "$name must be an integer between 1024 and 65535: $value" >&2
    exit 2
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$REDIS_STARTED" == "true" ]]; then
    docker rm -f "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  fi
  if [[ "$MONGO_STARTED" == "true" ]]; then
    docker rm -f "$MONGO_CONTAINER" >/dev/null 2>&1 || true
  fi
  exit "$status"
}

wait_for_mongo() {
  local attempts=60
  for (( attempt = 1; attempt <= attempts; attempt++ )); do
    if docker exec "$MONGO_CONTAINER" mongosh --quiet --eval \
      'quit(db.runCommand({ ping: 1 }).ok === 1 ? 0 : 1)' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "MongoDB baseline container did not become ready" >&2
  return 1
}

wait_for_redis() {
  local attempts=60
  for (( attempt = 1; attempt <= attempts; attempt++ )); do
    if docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q '^PONG$'; then
      return 0
    fi
    sleep 1
  done
  echo "Redis baseline container did not become ready" >&2
  return 1
}

main() {
  require_command docker
  require_command git
  if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]]; then
    echo "CRAWLER_BASELINE_RUN_ID contains unsupported characters: $RUN_ID" >&2
    exit 2
  fi
  validate_port CRAWLER_MONGO_PORT "$MONGO_PORT"
  validate_port CRAWLER_REDIS_PORT "$REDIS_PORT"
  if [[ ! "$REPEATS" =~ ^[0-9]+$ ]] || (( REPEATS < 1 || REPEATS > 20 )); then
    echo "CRAWLER_BASELINE_REPEATS must be an integer between 1 and 20: $REPEATS" >&2
    exit 2
  fi
  if [[ "$EXPECTED_FIND_COUNT" != "0" && "$EXPECTED_FIND_COUNT" != "6" ]]; then
    echo "CRAWLER_BASELINE_EXPECTED_FIND_COUNT must be 0 or 6: $EXPECTED_FIND_COUNT" >&2
    exit 2
  fi

  if [[ "$MONGO_PORT" == "$REDIS_PORT" ]]; then
    echo "MongoDB and Redis ports must be different" >&2
    exit 2
  fi

  export CRAWLER_BASELINE_COMMIT_SHA
  CRAWLER_BASELINE_COMMIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  export CRAWLER_BASELINE_GIT_DIRTY
  CRAWLER_BASELINE_GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]] && echo true || echo false)"
  if [[ "$CRAWLER_BASELINE_GIT_DIRTY" == "true" && "${ALLOW_DIRTY_BASELINE_RUN:-false}" != "true" ]]; then
    echo "Crawler baseline requires a clean worktree. Commit changes or set ALLOW_DIRTY_BASELINE_RUN=true for development-only verification." >&2
    exit 2
  fi
  export CRAWLER_BASELINE_HARNESS_SHA256
  CRAWLER_BASELINE_HARNESS_SHA256="$(
    cd "$ROOT_DIR"
    shasum -a 256 \
      src/integrationTest/kotlin/com/didimlog/application/problem/collector/ProblemCollectorBaselineIntegrationTest.kt \
      src/main/kotlin/com/didimlog/portfolio/PortfolioFixtureClients.kt \
      src/main/resources/application-portfolio-fixture.yaml \
      src/test/resources/application-test.yml \
      performance/crawler/run-baseline.sh |
        shasum -a 256 |
        awk '{print $1}'
  )"
  export CRAWLER_BASELINE_MONGO_IMAGE="$MONGO_IMAGE"
  export CRAWLER_BASELINE_REDIS_IMAGE="$REDIS_IMAGE"
  export CRAWLER_BASELINE_EXPECTED_FIND_COUNT="$EXPECTED_FIND_COUNT"

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if [[ -e "$OUTPUT_DIR" ]]; then
    echo "Crawler baseline output already exists: $OUTPUT_DIR" >&2
    exit 2
  fi
  mkdir -p "$OUTPUT_DIR"

  docker run --detach --rm \
    --name "$MONGO_CONTAINER" \
    --label didimlog.scope=crawler-baseline \
    --publish "127.0.0.1:$MONGO_PORT:27017" \
    --tmpfs /data/db \
    "$MONGO_IMAGE" >/dev/null
  MONGO_STARTED=true

  docker run --detach --rm \
    --name "$REDIS_CONTAINER" \
    --label didimlog.scope=crawler-baseline \
    --publish "127.0.0.1:$REDIS_PORT:6379" \
    "$REDIS_IMAGE" \
    redis-server --save "" --appendonly no >/dev/null
  REDIS_STARTED=true

  wait_for_mongo
  wait_for_redis

  export CRAWLER_BASELINE_ENABLED=true

  export SPRING_PROFILES_ACTIVE="test,portfolio-fixture"
  export SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:$MONGO_PORT/didimlog-crawler-baseline"
  export SPRING_DATA_REDIS_HOST="127.0.0.1"
  export SPRING_DATA_REDIS_PORT="$REDIS_PORT"
  export SPRING_DATA_REDIS_DATABASE=0
  export MAIL_PASSWORD="crawler-baseline-not-used"

  for (( iteration = 1; iteration <= REPEATS; iteration++ )); do
    local iteration_name
    iteration_name="$(printf 'run-%02d' "$iteration")"
    export CRAWLER_BASELINE_ITERATION="$iteration"
    export CRAWLER_BASELINE_OUTPUT_DIR="$OUTPUT_DIR/$iteration_name"

    "$ROOT_DIR/gradlew" integrationTest \
      --tests com.didimlog.application.problem.collector.ProblemCollectorBaselineIntegrationTest \
      --rerun-tasks

    test -f "$CRAWLER_BASELINE_OUTPUT_DIR/metadata-cold.json"
    test -f "$CRAWLER_BASELINE_OUTPUT_DIR/metadata-warm.json"
  done
  echo "Crawler baseline completed: $OUTPUT_DIR"
  echo "The 6-item itemsPerSecond values are diagnostic-only; use the raw runs for command-count characterization."
}

main "$@"
