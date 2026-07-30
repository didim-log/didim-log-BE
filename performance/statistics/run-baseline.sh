#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_ROOT="$ROOT_DIR/performance/results"
RUN_ID="${STATISTICS_QUERY_BASELINE_RUN_ID:-statistics-query-baseline-$(date +%Y%m%d%H%M%S)-$$}"
OUTPUT_DIR="$RESULTS_ROOT/$RUN_ID"

MONGO_IMAGE="mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a"
MONGO_PORT="${STATISTICS_QUERY_MONGO_PORT:-27219}"
MONGO_CONTAINER="$RUN_ID-mongo"
MONGO_STARTED=false

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

validate_inputs() {
  if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]]; then
    echo "STATISTICS_QUERY_BASELINE_RUN_ID contains unsupported characters: $RUN_ID" >&2
    exit 2
  fi
  if [[ ! "$MONGO_PORT" =~ ^[0-9]+$ ]] || (( MONGO_PORT < 1024 || MONGO_PORT > 65535 )); then
    echo "STATISTICS_QUERY_MONGO_PORT must be an integer between 1024 and 65535: $MONGO_PORT" >&2
    exit 2
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
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
  echo "MongoDB statistics baseline container did not become ready" >&2
  return 1
}

main() {
  require_command docker
  require_command git
  require_command shasum
  validate_inputs

  export STATISTICS_QUERY_BASELINE_COMMIT_SHA
  STATISTICS_QUERY_BASELINE_COMMIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  export STATISTICS_QUERY_BASELINE_GIT_DIRTY
  STATISTICS_QUERY_BASELINE_GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]] && echo true || echo false)"
  if [[ "$STATISTICS_QUERY_BASELINE_GIT_DIRTY" == "true" && "${ALLOW_DIRTY_BASELINE_RUN:-false}" != "true" ]]; then
    echo "Statistics baseline requires a clean worktree. Commit changes or set ALLOW_DIRTY_BASELINE_RUN=true for development-only verification." >&2
    exit 2
  fi

  export STATISTICS_QUERY_BASELINE_HARNESS_SHA256
  STATISTICS_QUERY_BASELINE_HARNESS_SHA256="$(
    cd "$ROOT_DIR"
    shasum -a 256 \
      src/integrationTest/kotlin/com/didimlog/application/statistics/StatisticsQueryBaselineIntegrationTest.kt \
      src/integrationTest/kotlin/com/didimlog/application/admin/query/MongoQueryPlanExplainer.kt \
      src/integrationTest/kotlin/com/didimlog/application/admin/query/AdminUserQueryPerformanceIntegrationTest.kt \
      performance/statistics/run-baseline.sh |
        shasum -a 256 |
        awk '{print $1}'
  )"

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  if [[ -e "$OUTPUT_DIR" ]]; then
    echo "Statistics baseline output already exists: $OUTPUT_DIR" >&2
    exit 2
  fi
  mkdir -p "$OUTPUT_DIR"

  docker run --detach --rm \
    --name "$MONGO_CONTAINER" \
    --label didimlog.scope=statistics-query-baseline \
    --publish "127.0.0.1:$MONGO_PORT:27017" \
    --tmpfs /data/db \
    "$MONGO_IMAGE" >/dev/null
  MONGO_STARTED=true

  wait_for_mongo

  export STATISTICS_QUERY_BASELINE_ENABLED=true
  export STATISTICS_QUERY_BASELINE_OUTPUT_DIR="$OUTPUT_DIR"
  export SPRING_PROFILES_ACTIVE=test
  export SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:$MONGO_PORT/didimlog-statistics-query-baseline"
  export TZ=Asia/Seoul

  "$ROOT_DIR/gradlew" integrationTest \
    --tests com.didimlog.application.statistics.StatisticsQueryBaselineIntegrationTest \
    --rerun-tasks \
    --no-daemon

  test -f "$OUTPUT_DIR/statistics-main.json"
  test -f "$OUTPUT_DIR/statistics-year-2024.json"

  echo "Statistics query baseline completed: $OUTPUT_DIR"
}

main "$@"
