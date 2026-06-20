#!/usr/bin/env bash
set -euo pipefail

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8090}"
MONGO_URI="${MONGO_URI:-mongodb://localhost:27017/didimlog-performance}"
EXPECTED_GEMINI_CALLS="${EXPECTED_GEMINI_CALLS:-1}"
EXPECTED_STATUS="${EXPECTED_STATUS:-COMPLETED}"
EXPECTED_REVIEW_COUNT="${EXPECTED_REVIEW_COUNT:-1}"
POLL_TIMEOUT_SECONDS="${AI_POLL_TIMEOUT_SECONDS:-30}"
POLL_INTERVAL_MILLIS="${AI_POLL_INTERVAL_MILLIS:-250}"
OUTPUT_JSON=""
LOG_ID=""
RUN_ID="${AI_RUN_ID:-}"

usage() {
  cat <<USAGE
Usage: performance/verify/verify_ai_call_count.sh (--run-id RUN_ID | --log-id LOG_ID) [options]

Options:
  --expect-gemini-calls N       Expected Gemini mock request count.
  --expect-status STATUS        Expected aiReviewStatus, for example COMPLETED or FAILED.
  --expect-review-count N       Expected saved aiReview count among matching logs.
  --poll-timeout-seconds N      Timeout for polling Gemini and Mongo state.
  --poll-interval-millis N      Poll interval in milliseconds.
  --output-json PATH            Write final verification JSON to PATH.

The script always validates WireMock request count and MongoDB final state from
strict JSON output. It does not use text grep for pass/fail decisions.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --log-id)
      LOG_ID="$2"
      shift 2
      ;;
    --run-id)
      RUN_ID="$2"
      shift 2
      ;;
    --expect-gemini-calls)
      EXPECTED_GEMINI_CALLS="$2"
      shift 2
      ;;
    --expect-status)
      EXPECTED_STATUS="$2"
      shift 2
      ;;
    --expect-review-count)
      EXPECTED_REVIEW_COUNT="$2"
      shift 2
      ;;
    --poll-timeout-seconds)
      POLL_TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --poll-interval-millis)
      POLL_INTERVAL_MILLIS="$2"
      shift 2
      ;;
    --output-json)
      OUTPUT_JSON="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required" >&2
    exit 127
  fi
}

require_command curl
require_command python3

if [[ -z "$LOG_ID" && -z "$RUN_ID" ]]; then
  echo "Either --log-id or --run-id is required" >&2
  exit 2
fi

python3 - "$MONGO_URI" <<'PY'
import sys
from urllib.parse import urlparse

uri = sys.argv[1]
parsed = urlparse(uri)
if parsed.scheme != "mongodb":
    raise SystemExit("MONGO_URI must use mongodb://")
if parsed.username or parsed.password:
    raise SystemExit("MONGO_URI must not contain credentials")
db_name = parsed.path.lstrip("/").split("?")[0]
if db_name != "didimlog-performance":
    raise SystemExit("MONGO_URI database must be didimlog-performance")
if parsed.hostname not in {"localhost", "127.0.0.1", "::1", "mongo", "host.docker.internal"}:
    raise SystemExit(f"MONGO_URI host must be local: {parsed.hostname}")
PY

get_gemini_count() {
  local count_json
  count_json="$(
    curl -fsS -X POST "$WIREMOCK_URL/__admin/requests/count" \
      -H "Content-Type: application/json" \
      -d '{"method":"POST","urlPathPattern":"/v1beta/models/.*:generateContent"}'
  )"
  python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("count", -1)))' <<<"$count_json"
}

mongo_result_with_mongosh() {
  local filter_js
  if [[ -n "$LOG_ID" ]]; then
    filter_js="const filter = { _id: '$LOG_ID' };"
  else
    filter_js="const filter = { title: 'k6-ai-review-$RUN_ID' };"
  fi

  mongosh "$MONGO_URI" --quiet --eval "
$filter_js
const docs = db.logs.find(filter).toArray();
function reviewText(review) {
  if (review === undefined || review === null) {
    return null;
  }
  if (typeof review === 'string') {
    return review;
  }
  if (review.value !== undefined && review.value !== null) {
    return String(review.value);
  }
  return String(review);
}
function numberValue(value) {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value === 'number') {
    return value;
  }
  if (typeof value.toNumber === 'function') {
    return value.toNumber();
  }
  if (value.low !== undefined && value.high !== undefined) {
    return value.high * 4294967296 + (value.low >>> 0);
  }
  const parsed = Number(value);
  return Number.isNaN(parsed) ? null : parsed;
}
const doc = docs.length > 0 ? docs[0] : null;
const savedCount = docs.filter((item) => reviewText(item.aiReview) !== null).length;
const review = doc ? reviewText(doc.aiReview) : null;
print(JSON.stringify({
  found: docs.length > 0,
  matchingLogCount: docs.length,
  logId: doc ? String(doc._id) : null,
  title: doc ? doc.title : null,
  aiReviewSavedCount: savedCount,
  duplicateAiReviewSavedCount: Math.max(0, savedCount - 1),
  aiReviewStatus: doc ? (doc.aiReviewStatus || null) : null,
  lockExpiresAtPresent: doc ? (doc.aiReviewLockExpiresAt !== undefined && doc.aiReviewLockExpiresAt !== null) : false,
  aiReviewDurationMillis: doc ? numberValue(doc.aiReviewDurationMillis) : null,
  reviewPresent: review !== null,
  reviewBlank: review !== null ? review.trim().length === 0 : null
}));
" | tail -n 1
}

mongo_result_with_export() {
  require_command mongoexport
  local docs_file
  docs_file="$(mktemp)"
  local export_query
  if [[ -n "$LOG_ID" ]]; then
    export_query="{\"_id\":\"$LOG_ID\"}"
  else
    export_query="{\"title\":\"k6-ai-review-$RUN_ID\"}"
  fi
  mongoexport --quiet --uri "$MONGO_URI" --collection logs --query "$export_query" --out "$docs_file" >/dev/null
  python3 - "$docs_file" <<'PY'
import json
import sys

docs_file = sys.argv[1]
with open(docs_file, encoding="utf-8") as f:
    docs = [json.loads(line) for line in f if line.strip()]

def review_text(review):
    if review is None:
        return None
    if isinstance(review, str):
        return review
    if isinstance(review, dict):
        return review.get("value") or review.get("$string")
    return str(review)

doc = docs[0] if docs else None
saved_count = sum(1 for item in docs if review_text(item.get("aiReview")) is not None)
review = review_text(doc.get("aiReview")) if doc else None
log_id = doc.get("_id") if doc else None
if isinstance(log_id, dict):
    log_id = log_id.get("$oid", str(log_id))

print(json.dumps({
    "found": bool(docs),
    "matchingLogCount": len(docs),
    "logId": log_id,
    "title": doc.get("title") if doc else None,
    "aiReviewSavedCount": saved_count,
    "duplicateAiReviewSavedCount": max(0, saved_count - 1),
    "aiReviewStatus": doc.get("aiReviewStatus") if doc else None,
    "lockExpiresAtPresent": doc.get("aiReviewLockExpiresAt") is not None if doc else False,
    "aiReviewDurationMillis": doc.get("aiReviewDurationMillis") if doc else None,
    "reviewPresent": review is not None,
    "reviewBlank": review.strip() == "" if review is not None else None,
}, ensure_ascii=False))
PY
  rm -f "$docs_file"
}

get_mongo_result() {
  if command -v mongosh >/dev/null 2>&1; then
    mongo_result_with_mongosh
  else
    mongo_result_with_export
  fi
}

combine_result() {
  local gemini_count="$1"
  local mongo_json="$2"
  python3 - "$gemini_count" "$mongo_json" "$EXPECTED_GEMINI_CALLS" "$EXPECTED_STATUS" "$EXPECTED_REVIEW_COUNT" <<'PY'
import json
import sys

gemini_count = int(sys.argv[1])
mongo = json.loads(sys.argv[2])
expected_gemini = int(sys.argv[3])
expected_status = sys.argv[4]
expected_review_count = int(sys.argv[5])

failures = []
if gemini_count != expected_gemini:
    failures.append("GEMINI_CALL_MISMATCH")
if mongo.get("matchingLogCount") != 1:
    failures.append("MATCHING_LOG_COUNT")
if mongo.get("aiReviewSavedCount") != expected_review_count:
    failures.append("AI_REVIEW_SAVED_COUNT")
if mongo.get("duplicateAiReviewSavedCount") != 0:
    failures.append("DUPLICATE_AI_REVIEW_SAVED")
if mongo.get("aiReviewStatus") != expected_status:
    failures.append("AI_REVIEW_STATUS")
if mongo.get("lockExpiresAtPresent") is not False:
    failures.append("AI_REVIEW_LOCK_REMAINS")

if expected_status == "COMPLETED":
    duration = mongo.get("aiReviewDurationMillis")
    if not isinstance(duration, (int, float)) or duration < 0:
        failures.append("AI_REVIEW_DURATION")
    if mongo.get("reviewPresent") is not True or mongo.get("reviewBlank") is not False:
        failures.append("AI_REVIEW_EMPTY")
elif expected_review_count == 0:
    if mongo.get("reviewPresent") is True:
        failures.append("UNEXPECTED_AI_REVIEW")

result = {
    **mongo,
    "geminiCallCount": gemini_count,
    "expectedGeminiCalls": expected_gemini,
    "expectedStatus": expected_status,
    "expectedReviewCount": expected_review_count,
    "result": "PASS" if not failures else "FAIL",
    "failureReasons": failures,
}
print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
PY
}

result_passed() {
  python3 -c 'import json,sys; sys.exit(0 if json.loads(sys.stdin.read()).get("result") == "PASS" else 1)' <<<"$1"
}

deadline=$(( $(date +%s) + POLL_TIMEOUT_SECONDS ))
last_result=""

while true; do
  gemini_count="$(get_gemini_count)"
  mongo_json="$(get_mongo_result)"
  last_result="$(combine_result "$gemini_count" "$mongo_json")"
  if result_passed "$last_result"; then
    break
  fi
  if [[ "$(date +%s)" -ge "$deadline" ]]; then
    break
  fi
  sleep "$(python3 - "$POLL_INTERVAL_MILLIS" <<'PY'
import sys
print(int(sys.argv[1]) / 1000)
PY
)"
done

echo "$last_result"
if [[ -n "$OUTPUT_JSON" ]]; then
  mkdir -p "$(dirname "$OUTPUT_JSON")"
  printf '%s\n' "$last_result" >"$OUTPUT_JSON"
fi

if ! result_passed "$last_result"; then
  exit 1
fi
