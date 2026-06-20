#!/usr/bin/env bash
set -euo pipefail

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8090}"
MONGO_URI="${MONGO_URI:-mongodb://localhost:27017/didimlog-performance}"
EXPECTED_GEMINI_CALLS="${EXPECTED_GEMINI_CALLS:-1}"
LOG_ID=""
RUN_ID="${AI_RUN_ID:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      cat <<USAGE
Usage: performance/verify/verify_ai_call_count.sh (--run-id RUN_ID | --log-id LOG_ID)

Verifies:
  - WireMock Gemini request count equals EXPECTED_GEMINI_CALLS (default 1)
  - MongoDB log has exactly one final aiReview
  - No duplicate reviewed log exists for the same k6 AI run title
  - aiReviewLockExpiresAt is removed after completion
USAGE
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
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 127
fi

GEMINI_COUNT_JSON="$(
  curl -fsS -X POST "$WIREMOCK_URL/__admin/requests/count" \
    -H "Content-Type: application/json" \
    -d '{"method":"POST","urlPathPattern":"/v1beta/models/.*:generateContent"}'
)"

GEMINI_COUNT="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", -1))' <<<"$GEMINI_COUNT_JSON")"

if [[ -n "$LOG_ID" ]]; then
  MONGO_FILTER="const filter = { _id: '$LOG_ID' };"
  EXPORT_QUERY="{\"_id\":\"$LOG_ID\"}"
elif [[ -n "$RUN_ID" ]]; then
  MONGO_FILTER="const filter = { title: 'k6-ai-review-$RUN_ID' };"
  EXPORT_QUERY="{\"title\":\"k6-ai-review-$RUN_ID\"}"
else
  echo "Either --log-id or --run-id is required" >&2
  exit 2
fi

if command -v mongosh >/dev/null 2>&1; then
  MONGO_RESULT="$(
    mongosh "$MONGO_URI" --quiet --eval "
$MONGO_FILTER
const doc = db.logs.findOne(filter);
if (!doc) {
  printjson({ found: false });
  quit(0);
}
const sameRunReviewed = db.logs.countDocuments({ title: doc.title, aiReview: { \$ne: null } });
printjson({
  found: true,
  logId: String(doc._id),
  title: doc.title,
  aiReviewSavedCount: doc.aiReview ? 1 : 0,
  duplicateAiReviewSavedCount: Math.max(0, sameRunReviewed - 1),
  aiReviewStatus: doc.aiReviewStatus || null,
  lockExpiresAtPresent: doc.aiReviewLockExpiresAt !== undefined && doc.aiReviewLockExpiresAt !== null
});
"
  )"
else
  if ! command -v mongoexport >/dev/null 2>&1; then
    echo "mongosh or mongoexport is required" >&2
    exit 127
  fi
  DOCS_FILE="$(mktemp)"
  REVIEWED_FILE="$(mktemp)"
  mongoexport --quiet --uri "$MONGO_URI" --collection logs --query "$EXPORT_QUERY" --out "$DOCS_FILE" >/dev/null
  python3 - "$DOCS_FILE" "$REVIEWED_FILE" "$MONGO_URI" <<'PY'
import json
import subprocess
import sys

docs_file, reviewed_file, mongo_uri = sys.argv[1], sys.argv[2], sys.argv[3]
with open(docs_file, encoding="utf-8") as f:
    docs = [json.loads(line) for line in f if line.strip()]

if not docs:
    print("{ found: false }")
    sys.exit(0)

doc = docs[0]
title = doc.get("title")
query = json.dumps({"title": title, "aiReview": {"$ne": None}}, ensure_ascii=False)
subprocess.run(
    ["mongoexport", "--quiet", "--uri", mongo_uri, "--collection", "logs", "--query", query, "--out", reviewed_file],
    check=True,
    stdout=subprocess.DEVNULL,
)
with open(reviewed_file, encoding="utf-8") as f:
    reviewed_count = sum(1 for line in f if line.strip())

ai_review_saved_count = 1 if doc.get("aiReview") is not None else 0
duplicate_count = max(0, reviewed_count - 1)
lock_present = doc.get("aiReviewLockExpiresAt") is not None
log_id = doc.get("_id")
if isinstance(log_id, dict):
    log_id = log_id.get("$oid", str(log_id))

print("{")
print("  found: true,")
print(f"  logId: '{log_id}',")
print(f"  title: '{title}',")
print(f"  aiReviewSavedCount: {ai_review_saved_count},")
print(f"  duplicateAiReviewSavedCount: {duplicate_count},")
print(f"  aiReviewStatus: '{doc.get('aiReviewStatus')}',")
print(f"  lockExpiresAtPresent: {'true' if lock_present else 'false'}")
print("}")
PY
  rm -f "$DOCS_FILE" "$REVIEWED_FILE"
fi

echo "Gemini actual call count: $GEMINI_COUNT"
echo "$MONGO_RESULT"

if [[ "$GEMINI_COUNT" != "$EXPECTED_GEMINI_CALLS" ]]; then
  echo "FAILED: expected Gemini calls=$EXPECTED_GEMINI_CALLS actual=$GEMINI_COUNT" >&2
  exit 1
fi

if ! grep -q "aiReviewSavedCount: 1" <<<"$MONGO_RESULT"; then
  echo "FAILED: final AI review saved count is not 1" >&2
  exit 1
fi

if ! grep -q "duplicateAiReviewSavedCount: 0" <<<"$MONGO_RESULT"; then
  echo "FAILED: duplicate AI review saved count is not 0" >&2
  exit 1
fi

if ! grep -q "lockExpiresAtPresent: false" <<<"$MONGO_RESULT"; then
  echo "FAILED: AI review lock expiry field remains after completion" >&2
  exit 1
fi

echo "AI concurrency verification passed."
