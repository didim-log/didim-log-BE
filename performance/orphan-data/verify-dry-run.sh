#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORPHAN_DIR="$ROOT_DIR/performance/orphan-data"
MONGO_IMAGE="mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a"
MONGO_CONTAINER="didimlog-orphan-verify-$$"
FIXTURE_DATABASE="didimlog-orphan-fixture"
ROOT_USER="orphan-admin"
ROOT_PASSWORD="fixture-admin-password"
MONGO_STARTED=false
TEMP_DIR=""

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$MONGO_STARTED" == "true" ]]; then
    docker rm -f "$MONGO_CONTAINER" >/dev/null 2>&1 || true
  fi
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -r "$TEMP_DIR"
  fi
  exit "$status"
}

wait_for_mongo() {
  local root_uri="$1"
  local attempts=60
  for (( attempt = 1; attempt <= attempts; attempt++ )); do
    if docker exec "$MONGO_CONTAINER" mongosh \
      "$root_uri" \
      --quiet \
      --norc \
      --eval 'quit(db.runCommand({ ping: 1 }).ok === 1 ? 0 : 1)' \
      >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "MongoDB orphan-data verification container did not become ready" >&2
  return 1
}

copy_assets() {
  docker cp \
    "$ORPHAN_DIR/orphan-data-dry-run.js" \
    "$MONGO_CONTAINER:/tmp/orphan-data-dry-run.js"
  docker cp \
    "$ORPHAN_DIR/fixtures/seed-fixture.js" \
    "$MONGO_CONTAINER:/tmp/seed-fixture.js"
  docker cp \
    "$ORPHAN_DIR/fixtures/seed-collision.js" \
    "$MONGO_CONTAINER:/tmp/seed-collision.js"
  docker cp \
    "$ORPHAN_DIR/fixtures/snapshot.js" \
    "$MONGO_CONTAINER:/tmp/snapshot.js"
}

run_audit() {
  local reader_uri="$1"
  local output_path="$2"
  local commit_sha="$3"
  local git_dirty="$4"
  local harness_sha="$5"
  local target_scope="${6:-local-fixture}"

  docker exec \
    --env ORPHAN_DRY_RUN_EXPECTED_DATABASE="$FIXTURE_DATABASE" \
    --env ORPHAN_DRY_RUN_TARGET_SCOPE="$target_scope" \
    --env ORPHAN_DRY_RUN_RUN_ID=orphan-fixture-verification \
    --env ORPHAN_DRY_RUN_COMMIT_SHA="$commit_sha" \
    --env ORPHAN_DRY_RUN_GIT_DIRTY="$git_dirty" \
    --env ORPHAN_DRY_RUN_HARNESS_SHA256="$harness_sha" \
    --env ORPHAN_DRY_RUN_MONGO_IMAGE="$MONGO_IMAGE" \
    --env ORPHAN_DRY_RUN_MAX_TIME_MS=30000 \
    "$MONGO_CONTAINER" \
    mongosh \
    "$reader_uri" \
    --quiet \
    --norc \
    --retryWrites=false \
    --file /tmp/orphan-data-dry-run.js >"$output_path"
}

assert_wrapper_rejects_uri() {
  local uri="$1"
  local expected_message="$2"
  local label="$3"

  set +e
  ORPHAN_DRY_RUN_MONGOSH_BIN=true \
    ORPHAN_DRY_RUN_MONGO_URI="$uri" \
    ORPHAN_DRY_RUN_EXPECTED_DATABASE=didimlog \
    ORPHAN_DRY_RUN_TARGET_SCOPE=remote-read-only \
    ORPHAN_DRY_RUN_RUN_ID="tls-rejection-$label" \
    "$ORPHAN_DIR/run-dry-run.sh" \
    >"$TEMP_DIR/wrapper-$label.log" 2>&1
  local status=$?
  set -e

  if (( status == 0 )); then
    echo "Remote dry-run wrapper accepted an unsafe TLS URI" >&2
    exit 2
  fi
  if ! grep -Fq "$expected_message" "$TEMP_DIR/wrapper-$label.log"; then
    echo "Remote dry-run wrapper failed for an unexpected reason" >&2
    exit 2
  fi
}

change_collision_fixture() {
  local fixture_root_uri="$1"
  local collection_name="$2"
  local action="$3"

  docker exec \
    --env ORPHAN_COLLISION_COLLECTION="$collection_name" \
    --env ORPHAN_COLLISION_ACTION="$action" \
    "$MONGO_CONTAINER" \
    mongosh \
    "$fixture_root_uri" \
    --quiet \
    --norc \
    --file /tmp/seed-collision.js >/dev/null
}

main() {
  require_command docker
  require_command git
  require_command grep
  require_command node
  require_command shasum

  if grep -En \
    '\.(insert|insertOne|insertMany|updateOne|updateMany|replaceOne|delete|deleteOne|deleteMany|remove|drop|dropDatabase|createIndex|createIndexes|bulkWrite|findAndModify)[[:space:]]*\(' \
    "$ORPHAN_DIR/orphan-data-dry-run.js"; then
    echo "Dry-run script contains a write-capable database method" >&2
    exit 2
  fi
  if grep -En '\$(out|merge)[^A-Za-z0-9_]' \
    "$ORPHAN_DIR/orphan-data-dry-run.js"; then
    echo "Dry-run script contains a write-capable aggregation stage" >&2
    exit 2
  fi

  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/didimlog-orphan-verify.XXXXXX")"
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  assert_wrapper_rejects_uri \
    "mongodb://reader:password@example.com/didimlog?authSource=didimlog" \
    "require tls=true or ssl=true" \
    mongodb-missing
  assert_wrapper_rejects_uri \
    "mongodb://reader:password@example.com/didimlog?tls=false" \
    "cannot disable TLS" \
    mongodb-disabled
  assert_wrapper_rejects_uri \
    "mongodb://reader:password@example.com/didimlog?tls=true&ssl=false" \
    "cannot disable TLS" \
    mongodb-conflicting
  assert_wrapper_rejects_uri \
    "mongodb+srv://reader:password@example.com/didimlog?tls=false" \
    "cannot disable TLS" \
    srv-tls-disabled
  assert_wrapper_rejects_uri \
    "mongodb+srv://reader:password@example.com/didimlog?ssl=false" \
    "cannot disable TLS" \
    srv-ssl-disabled

  docker run --detach --rm \
    --name "$MONGO_CONTAINER" \
    --label didimlog.scope=orphan-data-verification \
    --tmpfs /data/db \
    --env MONGO_INITDB_ROOT_USERNAME="$ROOT_USER" \
    --env MONGO_INITDB_ROOT_PASSWORD="$ROOT_PASSWORD" \
    "$MONGO_IMAGE" >/dev/null
  MONGO_STARTED=true

  local root_uri
  root_uri="mongodb://$ROOT_USER:$ROOT_PASSWORD@127.0.0.1:27017/admin?authSource=admin&directConnection=true"
  wait_for_mongo "$root_uri"
  copy_assets

  local fixture_root_uri
  fixture_root_uri="mongodb://$ROOT_USER:$ROOT_PASSWORD@127.0.0.1:27017/$FIXTURE_DATABASE?authSource=admin&directConnection=true"
  docker exec "$MONGO_CONTAINER" mongosh \
    "$fixture_root_uri" \
    --quiet \
    --norc \
    --file /tmp/seed-fixture.js >/dev/null

  local reader_uri
  reader_uri="mongodb://orphan-reader:fixture-reader-password@127.0.0.1:27017/$FIXTURE_DATABASE?authSource=$FIXTURE_DATABASE&directConnection=true&readPreference=secondaryPreferred"
  local commit_sha
  commit_sha="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  local git_dirty
  git_dirty="$(
    [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]] &&
      echo true ||
      echo false
  )"
  local harness_sha
  harness_sha="$(
    cd "$ROOT_DIR"
    shasum -a 256 \
      performance/orphan-data/orphan-data-dry-run.js \
      performance/orphan-data/run-dry-run.sh \
      performance/orphan-data/verify-dry-run.sh \
      performance/orphan-data/verify-result.js \
      performance/orphan-data/fixtures/seed-fixture.js \
      performance/orphan-data/fixtures/seed-collision.js \
      performance/orphan-data/fixtures/snapshot.js |
      shasum -a 256 |
      awk '{print $1}'
  )"

  docker exec "$MONGO_CONTAINER" mongosh \
    "$fixture_root_uri" \
    --quiet \
    --norc \
    --file /tmp/snapshot.js >"$TEMP_DIR/before.json"
  run_audit \
    "$reader_uri" \
    "$TEMP_DIR/report-1.json" \
    "$commit_sha" \
    "$git_dirty" \
    "$harness_sha"
  run_audit \
    "$reader_uri" \
    "$TEMP_DIR/report-2.json" \
    "$commit_sha" \
    "$git_dirty" \
    "$harness_sha"
  docker exec "$MONGO_CONTAINER" mongosh \
    "$fixture_root_uri" \
    --quiet \
    --norc \
    --file /tmp/snapshot.js >"$TEMP_DIR/after.json"

  set +e
  run_audit \
    "$fixture_root_uri" \
    "$TEMP_DIR/role-aborted.json" \
    "$commit_sha" \
    "$git_dirty" \
    "$harness_sha" \
    remote-read-only
  local role_status=$?
  set -e
  if (( role_status == 0 )); then
    echo "Remote dry-run accepted a write-capable MongoDB role" >&2
    exit 2
  fi

  change_collision_fixture "$fixture_root_uri" students insert
  set +e
  run_audit \
    "$reader_uri" \
    "$TEMP_DIR/student-collision-aborted.json" \
    "$commit_sha" \
    "$git_dirty" \
    "$harness_sha"
  local student_collision_status=$?
  set -e
  if (( student_collision_status == 0 )); then
    echo "Student canonical String/ObjectId collision was not rejected" >&2
    exit 2
  fi
  change_collision_fixture "$fixture_root_uri" students remove

  change_collision_fixture "$fixture_root_uri" templates insert
  set +e
  run_audit \
    "$reader_uri" \
    "$TEMP_DIR/template-collision-aborted.json" \
    "$commit_sha" \
    "$git_dirty" \
    "$harness_sha"
  local template_collision_status=$?
  set -e
  if (( template_collision_status == 0 )); then
    echo "Template canonical String/ObjectId collision was not rejected" >&2
    exit 2
  fi

  node "$ORPHAN_DIR/verify-result.js" \
    "$TEMP_DIR/report-1.json" \
    "$TEMP_DIR/report-2.json" \
    "$TEMP_DIR/before.json" \
    "$TEMP_DIR/after.json" \
    "$TEMP_DIR/role-aborted.json" \
    "$TEMP_DIR/student-collision-aborted.json" \
    "$TEMP_DIR/template-collision-aborted.json" \
    "$commit_sha" \
    "$harness_sha"

  echo "Orphan data dry-run verification passed"
}

main "$@"
