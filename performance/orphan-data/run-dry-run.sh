#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/performance/orphan-data/orphan-data-dry-run.js"
RESULTS_ROOT="$ROOT_DIR/performance/results"
RUN_ID="${ORPHAN_DRY_RUN_RUN_ID:-orphan-dry-run-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
OUTPUT_DIR="$RESULTS_ROOT/$RUN_ID"
REPORT_PATH="$OUTPUT_DIR/orphan-data-dry-run.json"
TEMP_REPORT_PATH="$OUTPUT_DIR/.orphan-data-dry-run.json.tmp"
MONGOSH_BIN="${ORPHAN_DRY_RUN_MONGOSH_BIN:-mongosh}"
MAX_TIME_MS="${ORPHAN_DRY_RUN_MAX_TIME_MS:-30000}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

validate_inputs() {
  if [[ -z "${ORPHAN_DRY_RUN_MONGO_URI:-}" ]]; then
    echo "ORPHAN_DRY_RUN_MONGO_URI is required" >&2
    exit 2
  fi
  if [[ -z "${ORPHAN_DRY_RUN_EXPECTED_DATABASE:-}" ]]; then
    echo "ORPHAN_DRY_RUN_EXPECTED_DATABASE is required" >&2
    exit 2
  fi
  if [[ ! "$ORPHAN_DRY_RUN_EXPECTED_DATABASE" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "ORPHAN_DRY_RUN_EXPECTED_DATABASE contains unsupported characters" >&2
    exit 2
  fi
  case "$ORPHAN_DRY_RUN_EXPECTED_DATABASE" in
    admin|config|local)
      echo "Reserved MongoDB databases cannot be audited" >&2
      exit 2
      ;;
  esac
  if [[ "${ORPHAN_DRY_RUN_TARGET_SCOPE:-}" != "local-fixture" &&
        "${ORPHAN_DRY_RUN_TARGET_SCOPE:-}" != "remote-read-only" ]]; then
    echo "ORPHAN_DRY_RUN_TARGET_SCOPE must be local-fixture or remote-read-only" >&2
    exit 2
  fi
  if [[ "$ORPHAN_DRY_RUN_TARGET_SCOPE" == "local-fixture" ]]; then
    if [[ ! "$ORPHAN_DRY_RUN_EXPECTED_DATABASE" =~ ^didimlog-orphan- ]]; then
      echo "local-fixture requires a didimlog-orphan-* database" >&2
      exit 2
    fi
    if [[ ! "$ORPHAN_DRY_RUN_MONGO_URI" =~ ^mongodb://([^/@]+@)?(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?/ ]]; then
      echo "local-fixture requires a loopback MongoDB URI" >&2
      exit 2
    fi
  else
    if [[ ! "$ORPHAN_DRY_RUN_MONGO_URI" =~ ^mongodb(\+srv)?://[^/@]+@ ]]; then
      echo "remote-read-only requires an authenticated MongoDB URI" >&2
      exit 2
    fi
    local remote_uri_lower
    remote_uri_lower="$(
      printf '%s' "$ORPHAN_DRY_RUN_MONGO_URI" |
        tr '[:upper:]' '[:lower:]'
    )"
    local tls_enabled_pattern='[?&](tls|ssl)=true(&|$)'
    local tls_disabled_pattern='[?&](tls|ssl)=false(&|$)'
    if [[ "$remote_uri_lower" =~ $tls_disabled_pattern ]]; then
      echo "remote-read-only MongoDB URIs cannot disable TLS" >&2
      exit 2
    fi
    if [[ "$remote_uri_lower" == mongodb://* &&
          ! "$remote_uri_lower" =~ $tls_enabled_pattern ]]; then
      echo "remote-read-only mongodb:// URIs require tls=true or ssl=true" >&2
      exit 2
    fi
  fi
  if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]]; then
    echo "ORPHAN_DRY_RUN_RUN_ID contains unsupported characters" >&2
    exit 2
  fi
  if [[ ! "$MAX_TIME_MS" =~ ^[0-9]+$ ]] ||
    (( MAX_TIME_MS < 1000 || MAX_TIME_MS > 300000 )); then
    echo "ORPHAN_DRY_RUN_MAX_TIME_MS must be between 1000 and 300000" >&2
    exit 2
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -f "$TEMP_REPORT_PATH" ]]; then
    rm "$TEMP_REPORT_PATH"
  fi
  if [[ -d "$OUTPUT_DIR" && ! -e "$REPORT_PATH" ]]; then
    rmdir "$OUTPUT_DIR" 2>/dev/null || true
  fi
  exit "$status"
}

main() {
  require_command "$MONGOSH_BIN"
  require_command git
  require_command node
  require_command shasum
  require_command tr
  validate_inputs

  if [[ -e "$OUTPUT_DIR" ]]; then
    echo "Dry-run output already exists: $OUTPUT_DIR" >&2
    exit 2
  fi

  export ORPHAN_DRY_RUN_RUN_ID="$RUN_ID"
  export ORPHAN_DRY_RUN_COMMIT_SHA
  ORPHAN_DRY_RUN_COMMIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  export ORPHAN_DRY_RUN_GIT_DIRTY
  ORPHAN_DRY_RUN_GIT_DIRTY="$(
    [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]] &&
      echo true ||
      echo false
  )"
  if [[ "$ORPHAN_DRY_RUN_GIT_DIRTY" == "true" &&
        "${ALLOW_DIRTY_ORPHAN_DRY_RUN:-false}" != "true" ]]; then
    echo "Dry-run requires a clean worktree. Commit changes or use ALLOW_DIRTY_ORPHAN_DRY_RUN=true for development-only verification." >&2
    exit 2
  fi
  export ORPHAN_DRY_RUN_HARNESS_SHA256
  ORPHAN_DRY_RUN_HARNESS_SHA256="$(
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
  export ORPHAN_DRY_RUN_MAX_TIME_MS="$MAX_TIME_MS"

  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  mkdir -p "$OUTPUT_DIR"

  set +e
  "$MONGOSH_BIN" \
    "$ORPHAN_DRY_RUN_MONGO_URI" \
    --quiet \
    --norc \
    --retryWrites=false \
    --file "$SCRIPT_PATH" >"$TEMP_REPORT_PATH"
  local mongosh_status=$?
  set -e

  node - "$TEMP_REPORT_PATH" <<'NODE'
const fs = require("fs");

const path = process.argv[2];
const raw = fs.readFileSync(path, "utf8").trim();
if (!raw) {
  throw new Error("Dry-run produced an empty report");
}
const report = JSON.parse(raw);
if (report.schemaVersion !== "didimlog-orphan-dry-run/v1") {
  throw new Error("Unexpected dry-run schema version");
}
if (report.mode !== "READ_ONLY_DRY_RUN") {
  throw new Error("Unexpected dry-run mode");
}
NODE

  mv "$TEMP_REPORT_PATH" "$REPORT_PATH"
  if (( mongosh_status != 0 )); then
    echo "Dry-run blocked. Review the report: $REPORT_PATH" >&2
    exit "$mongosh_status"
  fi

  echo "Orphan data dry-run completed: $REPORT_PATH"
  echo "This report does not authorize deletion."
}

main "$@"
