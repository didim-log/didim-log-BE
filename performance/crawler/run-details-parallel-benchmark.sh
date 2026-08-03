#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_ROOT="$ROOT_DIR/performance/results"
RUN_ID="${CRAWLER_DETAILS_BENCHMARK_RUN_ID:-crawler-details-$(date +%Y%m%d%H%M%S)-$$}"
OUTPUT_DIR="$RESULTS_ROOT/$RUN_ID"

ITEM_COUNT="${CRAWLER_DETAILS_BENCHMARK_ITEM_COUNT:-3400}"
DELAY_MILLIS="${CRAWLER_DETAILS_BENCHMARK_DELAY_MILLIS:-10}"
CONCURRENCY="${CRAWLER_DETAILS_BENCHMARK_CONCURRENCY:-4}"

MONGO_IMAGE="mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a"
REDIS_IMAGE="redis:7.2.5-alpine@sha256:6aaf3f5e6bc8a592fbfe2cccf19eb36d27c39d12dab4f4b01556b7449e7b1f44"
MONGO_PORT="${CRAWLER_DETAILS_MONGO_PORT:-27127}"
REDIS_PORT="${CRAWLER_DETAILS_REDIS_PORT:-6399}"
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

validate_integer_range() {
  local name="$1"
  local value="$2"
  local minimum="$3"
  local maximum="$4"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < minimum || value > maximum )); then
    echo "$name must be an integer between $minimum and $maximum: $value" >&2
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
  echo "MongoDB crawler-details benchmark container did not become ready" >&2
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
  echo "Redis crawler-details benchmark container did not become ready" >&2
  return 1
}

main() {
  require_command docker
  require_command git
  require_command shasum

  if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]]; then
    echo "CRAWLER_DETAILS_BENCHMARK_RUN_ID contains unsupported characters: $RUN_ID" >&2
    exit 2
  fi

  validate_integer_range CRAWLER_DETAILS_BENCHMARK_ITEM_COUNT "$ITEM_COUNT" 1 3400
  validate_integer_range CRAWLER_DETAILS_BENCHMARK_DELAY_MILLIS "$DELAY_MILLIS" 0 10000
  validate_integer_range CRAWLER_DETAILS_BENCHMARK_CONCURRENCY "$CONCURRENCY" 2 16
  validate_integer_range CRAWLER_DETAILS_MONGO_PORT "$MONGO_PORT" 1024 65535
  validate_integer_range CRAWLER_DETAILS_REDIS_PORT "$REDIS_PORT" 1024 65535

  if [[ "$MONGO_PORT" == "$REDIS_PORT" ]]; then
    echo "MongoDB and Redis ports must be different" >&2
    exit 2
  fi

  export CRAWLER_DETAILS_BENCHMARK_COMMIT_SHA
  CRAWLER_DETAILS_BENCHMARK_COMMIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  export CRAWLER_DETAILS_BENCHMARK_GIT_DIRTY
  CRAWLER_DETAILS_BENCHMARK_GIT_DIRTY="$(
    [[ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=all)" ]] && echo true || echo false
  )"
  if [[ "$CRAWLER_DETAILS_BENCHMARK_GIT_DIRTY" == "true" && \
    "${ALLOW_DIRTY_CRAWLER_DETAILS_BENCHMARK:-false}" != "true" ]]; then
    echo "Crawler-details benchmark requires a clean worktree. Commit changes or set ALLOW_DIRTY_CRAWLER_DETAILS_BENCHMARK=true for a development-only run." >&2
    exit 2
  fi

  export CRAWLER_DETAILS_BENCHMARK_HARNESS_SHA256
  CRAWLER_DETAILS_BENCHMARK_HARNESS_SHA256="$(
    cd "$ROOT_DIR"
    shasum -a 256 \
      src/integrationTest/kotlin/com/didimlog/application/problem/collector/ProblemCollectorDetailsParallelBenchmarkIntegrationTest.kt \
      src/main/kotlin/com/didimlog/application/problem/collector/ParallelProblemDetailsFetcher.kt \
      src/main/kotlin/com/didimlog/application/problem/collector/ProblemCollectorParallelProperties.kt \
      src/main/kotlin/com/didimlog/application/problem/collector/ProblemCollectorService.kt \
      src/main/kotlin/com/didimlog/domain/repository/ProblemRepositoryImpl.kt \
      performance/crawler/run-details-parallel-benchmark.sh |
      shasum -a 256 |
      awk '{print $1}'
  )"
  export CRAWLER_DETAILS_BENCHMARK_MONGO_IMAGE="$MONGO_IMAGE"
  export CRAWLER_DETAILS_BENCHMARK_REDIS_IMAGE="$REDIS_IMAGE"
  export CRAWLER_DETAILS_BENCHMARK_ENABLED=true
  export CRAWLER_DETAILS_BENCHMARK_ITEM_COUNT="$ITEM_COUNT"
  export CRAWLER_DETAILS_BENCHMARK_DELAY_MILLIS="$DELAY_MILLIS"
  export CRAWLER_DETAILS_BENCHMARK_CONCURRENCY="$CONCURRENCY"
  export CRAWLER_DETAILS_BENCHMARK_OUTPUT_DIR="$OUTPUT_DIR"

  if [[ -e "$OUTPUT_DIR" ]]; then
    echo "Crawler-details benchmark output already exists: $OUTPUT_DIR" >&2
    exit 2
  fi
  mkdir -p "$OUTPUT_DIR"

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  docker run --detach --rm \
    --name "$MONGO_CONTAINER" \
    --label didimlog.scope=crawler-details-benchmark \
    --publish "127.0.0.1:$MONGO_PORT:27017" \
    --tmpfs /data/db \
    "$MONGO_IMAGE" >/dev/null
  MONGO_STARTED=true

  docker run --detach --rm \
    --name "$REDIS_CONTAINER" \
    --label didimlog.scope=crawler-details-benchmark \
    --publish "127.0.0.1:$REDIS_PORT:6379" \
    "$REDIS_IMAGE" \
    redis-server --save "" --appendonly no >/dev/null
  REDIS_STARTED=true

  wait_for_mongo
  wait_for_redis

  export SPRING_PROFILES_ACTIVE="test,portfolio-fixture"
  export SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:$MONGO_PORT/didimlog-crawler-details-benchmark"
  export SPRING_DATA_REDIS_HOST="127.0.0.1"
  export SPRING_DATA_REDIS_PORT="$REDIS_PORT"
  export SPRING_DATA_REDIS_DATABASE=0
  export MAIL_PASSWORD="crawler-details-benchmark-not-used"
  export PROBLEM_COLLECTOR_PARALLEL_ENABLED=false

  "$ROOT_DIR/gradlew" integrationTest \
    --tests com.didimlog.application.problem.collector.ProblemCollectorDetailsParallelBenchmarkIntegrationTest \
    --rerun-tasks

  test -f "$OUTPUT_DIR/sequential.json"
  test -f "$OUTPUT_DIR/parallel-k$CONCURRENCY.json"
  test -f "$OUTPUT_DIR/comparison.json"

  echo "Crawler-details benchmark completed: $OUTPUT_DIR"
  echo "This is a local fixed-delay comparison. It does not call BOJ and must not be reported as external-service wall time."
}

main "$@"
