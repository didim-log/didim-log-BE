#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/performance/mock-external/docker-compose.performance.yml"
K6_VERSION_FILE="$ROOT_DIR/performance/k6/K6_VERSION"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.performance}"
OUTPUT_JSON=""

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

usage() {
  cat <<USAGE
Usage: performance/verify/write_environment_manifest.sh [--output PATH]

Writes an allowlist-only performance environment manifest. Secret values,
credentials, tokens, and the full process environment are never serialized.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      if [[ $# -lt 2 ]]; then
        echo "--output requires a path" >&2
        exit 2
      fi
      OUTPUT_JSON="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

EXPECTED_APPLICATION_BASELINE_SHA="74f7941d8d28275b9abe38877f32c4216955350b"

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

: "${K6_RUN_ID:=perf-$(date +%Y%m%d%H%M%S)}"
: "${OUTPUT_JSON:=$ROOT_DIR/performance/results/environment-$K6_RUN_ID.json}"
: "${PERFORMANCE_PROTOCOL_ID:=be-refactor-phase0b-v1}"
: "${PROTOCOL_JSON:=$ROOT_DIR/performance/results/protocol-$PERFORMANCE_PROTOCOL_ID.json}"
: "${APPLICATION_BASELINE_SHA:=$EXPECTED_APPLICATION_BASELINE_SHA}"
: "${APPLICATION_WORKTREE:=$ROOT_DIR}"
if [[ "$APPLICATION_WORKTREE" != /* ]]; then
  APPLICATION_WORKTREE="$ROOT_DIR/$APPLICATION_WORKTREE"
fi
APPLICATION_WORKTREE="$(cd "$APPLICATION_WORKTREE" && pwd)"
APPLICATION_COMMIT_SHA="$(git -C "$APPLICATION_WORKTREE" rev-parse HEAD)"
APPLICATION_GIT_DIRTY="$([[ -n "$(git -C "$APPLICATION_WORKTREE" status --porcelain)" ]] && echo true || echo false)"
HARNESS_COMMIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
HARNESS_GIT_DIRTY="$([[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]] && echo true || echo false)"
: "${ALLOW_DIRTY_PERFORMANCE_RUN:=false}"
: "${APP_RUNTIME_MODE:=gradle-toolchain}"
: "${APP_JAVA_VERSION:=17}"
: "${APP_JVM_XMS:=512m}"
: "${APP_JVM_XMX:=512m}"
: "${APP_JVM_GC:=G1}"
: "${APP_TIMEZONE:=Asia/Seoul}"
: "${MOCK_GEMINI_MODE:=wiremock}"
: "${MOCK_GEMINI_DELAY_MS:=500}"
: "${BASE_URL:=http://localhost:8080}"
: "${SERVER_PORT:=8080}"
: "${WIREMOCK_URL:=http://localhost:8090}"
: "${WIREMOCK_PORT:=8090}"
: "${MONGO_URI:=mongodb://localhost:27017/didimlog-performance}"
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
: "${SERVER_URL:=http://localhost:8080}"
: "${JWT_SECRET:=performance-secret-key-must-be-at-least-256-bits-long-1234567890}"
: "${JWT_ACCESS_TOKEN_EXPIRATION:=1800000}"
: "${JWT_REFRESH_TOKEN_EXPIRATION:=604800000}"
: "${JWT_EXPIRATION:=1800000}"
: "${ADMIN_SECRET_KEY:=performance-admin-secret}"
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
: "${MONGO_ENVIRONMENT:=local-docker}"
: "${REDIS_ENVIRONMENT:=local-docker}"
: "${FIXTURE_VERSION:=be-refactor-phase0b-v1}"
: "${PERF_FIXTURE_EPOCH:=2026-07-26T00:00:00Z}"
: "${PERF_FIXTURE_RETROSPECTIVES:=100}"
: "${PERF_BOJ_ID:=perfuser}"
: "${PERF_AI_BOJ_ID_PREFIX:=perfuser_ai}"
: "${PERF_PASSWORD:=PerfPassword123!}"
: "${PERF_BCRYPT_PASSWORD:=\$2y\$10\$FTcPZSUl3qvlezqQQb7oreLZ8T2XID88ICjFjXipc2Ei4EfS7k9SO}"
: "${JWT_TTL_SECONDS:=3600}"
: "${FIXTURE_COUNT:=$PERF_FIXTURE_RETROSPECTIVES}"
: "${MEASUREMENT_REPETITION_INDEX:=1}"
: "${MEASUREMENT_REPETITION_TOTAL:=5}"
: "${READ_VUS:=10}"
: "${READ_DURATION:=1m}"
: "${READ_DASHBOARD_WEIGHT:=30}"
: "${READ_STATISTICS_WEIGHT:=25}"
: "${READ_LOG_LIST_WEIGHT:=30}"
: "${READ_LOG_DETAIL_WEIGHT:=15}"
: "${READ_PAGE_SIZE:=10}"
: "${READ_MAX_PAGE:=5}"
: "${READ_SLEEP_SECONDS:=0.1}"
: "${READ_RETROSPECTIVE_IDS:=}"
: "${AI_CONCURRENCY:=50}"
: "${AI_REPEAT_COUNT:=10}"
: "${AI_SYNC_WAIT_MS:=3000}"
: "${AI_MAX_DURATION:=45s}"
: "${AI_POLL_TIMEOUT_SECONDS:=30}"
: "${AI_POLL_INTERVAL_MILLIS:=250}"
: "${AI_FAILED_POLL_TIMEOUT_SECONDS:=20}"
: "${AI_COMPLETED_POLL_TIMEOUT_SECONDS:=30}"
: "${EXPECTED_GEMINI_CALLS:=1}"
: "${FAIL_FAST_AI_REPEAT:=false}"
: "${RATE_LIMIT_SIGNUP_MAX:=5}"
: "${RATE_LIMIT_LOGIN_MAX:=10}"
: "${RATE_LIMIT_PASSWORD_RESET_MAX:=3}"
: "${RATE_LIMIT_OVERAGE_REQUESTS:=2}"
: "${RATE_LIMIT_SLEEP_SECONDS:=0.05}"
: "${RATE_LIMIT_IP_PREFIX:=10.67}"
: "${RATE_LIMIT_SIGNUP_IP:=10.67.1.11}"
: "${RATE_LIMIT_LOGIN_IP:=10.67.1.12}"
: "${RATE_LIMIT_PASSWORD_RESET_IP:=10.67.1.13}"
: "${P95_MS:=}"

export MONGO_PORT REDIS_PORT WIREMOCK_PORT

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required to resolve immutable image references" >&2
  exit 127
fi

compose_json="$(mktemp)"
trap 'rm -f "$compose_json"' EXIT
docker compose -f "$COMPOSE_FILE" config --format json >"$compose_json"

if command -v java >/dev/null 2>&1; then
  SHELL_JAVA_VERSION="$(java -version 2>&1 | awk '/^(openjdk|java) version "/ { print; exit }')"
  : "${SHELL_JAVA_VERSION:=NOT_CAPTURED}"
else
  SHELL_JAVA_VERSION="NOT_CAPTURED"
fi

if command -v k6 >/dev/null 2>&1; then
  K6_ACTUAL_VERSION="$(k6 version 2>/dev/null | head -n 1)"
else
  K6_ACTUAL_VERSION="NOT_CAPTURED"
fi

HOST_OS="$(uname -sr)"
HOST_ARCH="$(uname -m)"
HOST_CPU="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | awk -F: '/Model name/ { sub(/^[ \t]+/, "", $2); print $2; exit }' || echo NOT_CAPTURED)"
HOST_MEMORY_BYTES="$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ { print $2 * 1024; exit }' /proc/meminfo 2>/dev/null || echo NOT_CAPTURED)"
K6_EXPECTED_VERSION="$(tr -d '[:space:]' <"$K6_VERSION_FILE")"

export K6_RUN_ID PERFORMANCE_PROTOCOL_ID APPLICATION_BASELINE_SHA APPLICATION_WORKTREE
export APPLICATION_COMMIT_SHA APPLICATION_GIT_DIRTY
export EXPECTED_APPLICATION_BASELINE_SHA
export HARNESS_COMMIT_SHA HARNESS_GIT_DIRTY ALLOW_DIRTY_PERFORMANCE_RUN
export APP_RUNTIME_MODE APP_JAVA_VERSION APP_JVM_XMS APP_JVM_XMX APP_JVM_GC APP_TIMEZONE
export MOCK_GEMINI_MODE MOCK_GEMINI_DELAY_MS BASE_URL SERVER_PORT WIREMOCK_URL WIREMOCK_PORT
export MONGO_URI MONGO_PORT REDIS_HOST REDIS_PORT REDIS_DATABASE
export SPRING_DATA_MONGODB_URI SPRING_DATA_REDIS_HOST SPRING_DATA_REDIS_PORT
export SPRING_DATA_REDIS_DATABASE SPRING_PROFILES_ACTIVE SPRING_CONFIG_IMPORT
export SPRING_MAIL_HOST SPRING_MAIL_PORT MAIL_PASSWORD
export OAUTH_GOOGLE_ID OAUTH_GOOGLE_SECRET OAUTH_GITHUB_ID OAUTH_GITHUB_SECRET
export OAUTH_NAVER_ID OAUTH_NAVER_SECRET SERVER_URL JWT_SECRET
export JWT_ACCESS_TOKEN_EXPIRATION JWT_REFRESH_TOKEN_EXPIRATION JWT_EXPIRATION
export ADMIN_SECRET_KEY AI_ENABLED GEMINI_API_KEY GEMINI_API_URL
export GEMINI_CONNECT_TIMEOUT_MILLIS GEMINI_RESPONSE_TIMEOUT_SECONDS
export GEMINI_READ_TIMEOUT_SECONDS GEMINI_WRITE_TIMEOUT_SECONDS GEMINI_MAX_RETRIES
export GEMINI_RETRY_BACKOFF_MILLIS
export AI_REVIEW_ASYNC_CORE_POOL_SIZE AI_REVIEW_ASYNC_MAX_POOL_SIZE AI_REVIEW_ASYNC_QUEUE_CAPACITY
export MONGO_ENVIRONMENT REDIS_ENVIRONMENT
export FIXTURE_VERSION PERF_FIXTURE_EPOCH PERF_FIXTURE_RETROSPECTIVES FIXTURE_COUNT
export PERF_BOJ_ID PERF_AI_BOJ_ID_PREFIX PERF_PASSWORD PERF_BCRYPT_PASSWORD JWT_TTL_SECONDS
export MEASUREMENT_REPETITION_INDEX MEASUREMENT_REPETITION_TOTAL
export READ_VUS READ_DURATION READ_DASHBOARD_WEIGHT READ_STATISTICS_WEIGHT
export READ_LOG_LIST_WEIGHT READ_LOG_DETAIL_WEIGHT READ_PAGE_SIZE READ_MAX_PAGE READ_SLEEP_SECONDS
export READ_RETROSPECTIVE_IDS
export AI_CONCURRENCY AI_REPEAT_COUNT AI_SYNC_WAIT_MS AI_MAX_DURATION AI_POLL_TIMEOUT_SECONDS
export AI_POLL_INTERVAL_MILLIS AI_FAILED_POLL_TIMEOUT_SECONDS AI_COMPLETED_POLL_TIMEOUT_SECONDS
export EXPECTED_GEMINI_CALLS FAIL_FAST_AI_REPEAT
export RATE_LIMIT_SIGNUP_MAX RATE_LIMIT_LOGIN_MAX RATE_LIMIT_PASSWORD_RESET_MAX
export RATE_LIMIT_OVERAGE_REQUESTS RATE_LIMIT_SLEEP_SECONDS P95_MS
export RATE_LIMIT_IP_PREFIX RATE_LIMIT_SIGNUP_IP RATE_LIMIT_LOGIN_IP RATE_LIMIT_PASSWORD_RESET_IP
export SHELL_JAVA_VERSION K6_ACTUAL_VERSION K6_EXPECTED_VERSION
export HOST_OS HOST_ARCH HOST_CPU HOST_MEMORY_BYTES

python3 - \
  "$OUTPUT_JSON" \
  "$compose_json" \
  "$APPLICATION_WORKTREE/build.gradle.kts" \
  "$ROOT_DIR/performance" \
  "$PROTOCOL_JSON" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
from datetime import datetime, timezone
from urllib.parse import urlparse

output_path, compose_path, build_path, performance_root, protocol_path = sys.argv[1:]


def value(name):
    return os.environ[name]


def boolean(name):
    raw = value(name).lower()
    if raw not in {"true", "false"}:
        raise SystemExit(f"{name} must be true or false")
    return raw == "true"


def integer(name, minimum=0):
    raw = value(name)
    if not re.fullmatch(r"0|[1-9]\d*", raw):
        raise SystemExit(f"{name} must be an integer")
    parsed = int(raw)
    if parsed < minimum:
        raise SystemExit(f"{name} must be at least {minimum}")
    return parsed


def number(name, minimum=0):
    raw = value(name)
    if not re.fullmatch(r"(0|[1-9]\d*)(\.\d+)?", raw):
        raise SystemExit(f"{name} must be a number")
    parsed = float(raw)
    if parsed < minimum:
        raise SystemExit(f"{name} must be at least {minimum}")
    return parsed


def optional_number(name, minimum=0):
    raw = value(name)
    if raw == "":
        return None
    return number(name, minimum)


def duration(name):
    raw = value(name)
    if not re.fullmatch(r"(0|[1-9]\d*)(ms|s|m|h)", raw):
        raise SystemExit(f"{name} must be a k6 duration")
    return raw


def commit_sha(name):
    raw = value(name)
    if not re.fullmatch(r"[0-9a-f]{40}", raw):
        raise SystemExit(f"{name} must be a full 40-character lowercase Git SHA")
    return raw


def heap_size(name):
    raw = value(name)
    if not re.fullmatch(r"[1-9]\d*[kKmMgG]", raw):
        raise SystemExit(f"{name} must use a JVM heap size such as 512m or 1g")
    return raw.lower()


runtime_mode = value("APP_RUNTIME_MODE")
configured_java_version = value("APP_JAVA_VERSION")
run_id = value("K6_RUN_ID")
if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", run_id):
    raise SystemExit("K6_RUN_ID must be 1-81 characters using letters, digits, dot, underscore, or hyphen")
protocol_id = value("PERFORMANCE_PROTOCOL_ID")
if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,80}", protocol_id):
    raise SystemExit(
        "PERFORMANCE_PROTOCOL_ID must be 1-81 lowercase characters using letters, digits, dot, underscore, or hyphen"
    )
if not re.fullmatch(r"[1-9]\d*", configured_java_version):
    raise SystemExit("APP_JAVA_VERSION must be a Java major version such as 17 or 21")

if runtime_mode != "gradle-toolchain":
    raise SystemExit("APP_RUNTIME_MODE must be gradle-toolchain for the Phase 0B baseline")

try:
    with open(build_path, encoding="utf-8") as source:
        build_source = source.read()
except FileNotFoundError as error:
    raise SystemExit(f"Cannot detect Gradle toolchain: {build_path} does not exist") from error
match = re.search(
    r"languageVersion\s*=\s*JavaLanguageVersion\.of\(\s*(\d+)\s*\)",
    build_source,
)
if not match:
    raise SystemExit("Cannot detect Java toolchain version from build.gradle.kts")
detected_java_version = match.group(1)
java_detection_source = "build.gradle.kts java.toolchain"

if configured_java_version != detected_java_version:
    raise SystemExit(
        "APP_JAVA_VERSION does not match the selected application runtime: "
        f"configured={configured_java_version}, detected={detected_java_version}, "
        f"mode={runtime_mode}"
    )

baseline_sha = commit_sha("APPLICATION_BASELINE_SHA")
if baseline_sha != commit_sha("EXPECTED_APPLICATION_BASELINE_SHA"):
    raise SystemExit(
        "APPLICATION_BASELINE_SHA does not match the Phase 0B baseline: "
        f"configured={baseline_sha}, expected={value('EXPECTED_APPLICATION_BASELINE_SHA')}"
    )

configured_gc = value("APP_JVM_GC")
if configured_gc != "G1":
    raise SystemExit("APP_JVM_GC must be G1 for the Phase 0B baseline")
if value("APP_TIMEZONE") != "Asia/Seoul":
    raise SystemExit("APP_TIMEZONE must be Asia/Seoul for the Phase 0B baseline")

configured_heap_initial = heap_size("APP_JVM_XMS")
configured_heap_maximum = heap_size("APP_JVM_XMX")
if configured_heap_initial != "512m" or configured_heap_maximum != "512m":
    raise SystemExit("APP_JVM_XMS and APP_JVM_XMX must both be 512m for the Phase 0B baseline")

expected_java_tool_options = (
    f"-Xms{configured_heap_initial} "
    f"-Xmx{configured_heap_maximum} "
    f"-XX:+Use{configured_gc}GC"
)
if os.environ.get("JAVA_TOOL_OPTIONS") != expected_java_tool_options:
    raise SystemExit(
        "JAVA_TOOL_OPTIONS must exactly match the configured Phase 0B JVM settings: "
        f"{expected_java_tool_options}"
    )
if os.environ.get("_JAVA_OPTIONS") or os.environ.get("JDK_JAVA_OPTIONS"):
    raise SystemExit("_JAVA_OPTIONS and JDK_JAVA_OPTIONS must be unset for the Phase 0B baseline")
if os.environ.get("SPRING_APPLICATION_JSON"):
    raise SystemExit("SPRING_APPLICATION_JSON must be unset for the Phase 0B baseline")

fixed_safe_application_values = {
    "SPRING_PROFILES_ACTIVE": "default",
    "SPRING_CONFIG_IMPORT": "optional:classpath:/didimlog-performance-no-external-config.properties",
    "SPRING_MAIL_HOST": "127.0.0.1",
    "SPRING_MAIL_PORT": "1",
    "MAIL_PASSWORD": "performance-not-used",
    "OAUTH_GOOGLE_ID": "performance-google-id",
    "OAUTH_GOOGLE_SECRET": "performance-google-secret",
    "OAUTH_GITHUB_ID": "performance-github-id",
    "OAUTH_GITHUB_SECRET": "performance-github-secret",
    "OAUTH_NAVER_ID": "performance-naver-id",
    "OAUTH_NAVER_SECRET": "performance-naver-secret",
    "JWT_SECRET": "performance-secret-key-must-be-at-least-256-bits-long-1234567890",
    "JWT_ACCESS_TOKEN_EXPIRATION": "1800000",
    "JWT_REFRESH_TOKEN_EXPIRATION": "604800000",
    "JWT_EXPIRATION": "1800000",
    "ADMIN_SECRET_KEY": "performance-admin-secret",
    "AI_ENABLED": "false",
    "GEMINI_API_KEY": "local-gemini-key",
    "PERF_BOJ_ID": "perfuser",
    "PERF_AI_BOJ_ID_PREFIX": "perfuser_ai",
    "PERF_PASSWORD": "PerfPassword123!",
    "PERF_BCRYPT_PASSWORD": "$2y$10$FTcPZSUl3qvlezqQQb7oreLZ8T2XID88ICjFjXipc2Ei4EfS7k9SO",
}
for name, expected in fixed_safe_application_values.items():
    if value(name) != expected:
        raise SystemExit(f"{name} must match the fixed local-only Phase 0B value")


with open(compose_path, encoding="utf-8") as source:
    compose = json.load(source)

service_names = {
    "mongo": "mongo",
    "redis": "redis",
    "wiremock": "gemini-wiremock",
}
images = {}
for output_name, service_name in service_names.items():
    image = compose.get("services", {}).get(service_name, {}).get("image")
    if not isinstance(image, str) or not re.fullmatch(r".+@sha256:[0-9a-f]{64}", image):
        raise SystemExit(f"{service_name} must use an immutable sha256 image reference")
    images[output_name] = image


def compose_published_port(service_name, target_port):
    ports = compose.get("services", {}).get(service_name, {}).get("ports", [])
    matches = [
        port
        for port in ports
        if port.get("protocol", "tcp") == "tcp" and int(port.get("target", -1)) == target_port
    ]
    if len(matches) != 1:
        raise SystemExit(f"{service_name} must publish target port {target_port} exactly once")
    binding = matches[0]
    if binding.get("host_ip") != "127.0.0.1":
        raise SystemExit(f"{service_name} must bind its published port to 127.0.0.1")
    try:
        published = int(binding["published"])
    except (KeyError, TypeError, ValueError) as error:
        raise SystemExit(f"{service_name} published port is invalid") from error
    if not 1 <= published <= 65535:
        raise SystemExit(f"{service_name} published port must be between 1 and 65535")
    return published


compose_ports = {
    "mongo": compose_published_port("mongo", 27017),
    "redis": compose_published_port("redis", 6379),
    "wiremock": compose_published_port("gemini-wiremock", 8080),
}

mock_mode = value("MOCK_GEMINI_MODE")
if mock_mode != "wiremock":
    raise SystemExit("MOCK_GEMINI_MODE must be wiremock for the Phase 0B baseline")

local_hosts = {"localhost", "127.0.0.1", "::1", "mongo", "redis", "gemini-wiremock", "host.docker.internal"}

server_port = integer("SERVER_PORT", 1)
if server_port > 65535:
    raise SystemExit("SERVER_PORT must not exceed 65535")
base_url = urlparse(value("BASE_URL"))
if (
    base_url.scheme != "http"
    or base_url.username
    or base_url.password
    or base_url.hostname not in local_hosts
    or (base_url.port or 80) != server_port
    or base_url.path not in {"", "/"}
    or base_url.query
    or base_url.fragment
):
    raise SystemExit("BASE_URL must be a credential-free local HTTP URL matching SERVER_PORT")
if value("SERVER_URL") != value("BASE_URL"):
    raise SystemExit("SERVER_URL must exactly match BASE_URL")

wiremock_url = urlparse(value("WIREMOCK_URL"))
if (
    wiremock_url.scheme != "http"
    or wiremock_url.username
    or wiremock_url.password
    or wiremock_url.hostname not in local_hosts
    or wiremock_url.path not in {"", "/"}
    or wiremock_url.query
    or wiremock_url.fragment
):
    raise SystemExit("WIREMOCK_URL must be a credential-free local HTTP origin")
configured_wiremock_port = integer("WIREMOCK_PORT", 1)
wiremock_endpoint_port = wiremock_url.port or 80
if (
    configured_wiremock_port > 65535
    or configured_wiremock_port != wiremock_endpoint_port
    or configured_wiremock_port != compose_ports["wiremock"]
):
    raise SystemExit("WIREMOCK_PORT, WIREMOCK_URL, and Compose published port must match")

mongo = urlparse(value("MONGO_URI"))
database_name = mongo.path.lstrip("/").split("?")[0]
if (
    mongo.scheme != "mongodb"
    or mongo.username
    or mongo.password
    or mongo.hostname not in local_hosts
    or database_name != "didimlog-performance"
    or mongo.query
    or mongo.fragment
):
    raise SystemExit("MONGO_URI must be a credential-free local didimlog-performance database")
configured_mongo_port = integer("MONGO_PORT", 1)
mongo_endpoint_port = mongo.port or 27017
if (
    configured_mongo_port > 65535
    or configured_mongo_port != mongo_endpoint_port
    or configured_mongo_port != compose_ports["mongo"]
):
    raise SystemExit("MONGO_PORT, MONGO_URI, and Compose published port must match")
if value("SPRING_DATA_MONGODB_URI") != value("MONGO_URI"):
    raise SystemExit("SPRING_DATA_MONGODB_URI must exactly match MONGO_URI")

redis_host = value("REDIS_HOST")
redis_port = integer("REDIS_PORT", 1)
if redis_port > 65535:
    raise SystemExit("REDIS_PORT must not exceed 65535")
if redis_port != compose_ports["redis"]:
    raise SystemExit("REDIS_PORT and Compose published port must match")
redis_database = integer("REDIS_DATABASE")
if redis_database > 15:
    raise SystemExit("REDIS_DATABASE must be between 0 and 15")
if redis_host not in local_hosts:
    raise SystemExit("REDIS_HOST must be local")
if value("SPRING_DATA_REDIS_HOST") != redis_host:
    raise SystemExit("SPRING_DATA_REDIS_HOST must exactly match REDIS_HOST")
spring_redis_port = integer("SPRING_DATA_REDIS_PORT", 1)
if spring_redis_port > 65535 or spring_redis_port != redis_port:
    raise SystemExit("SPRING_DATA_REDIS_PORT must exactly match REDIS_PORT")
spring_redis_database = integer("SPRING_DATA_REDIS_DATABASE")
if spring_redis_database > 15 or spring_redis_database != redis_database:
    raise SystemExit("SPRING_DATA_REDIS_DATABASE must exactly match REDIS_DATABASE")

gemini_url = urlparse(value("GEMINI_API_URL"))
gemini_origin = (gemini_url.scheme, gemini_url.hostname, gemini_url.port or 80)
wiremock_origin = (wiremock_url.scheme, wiremock_url.hostname, wiremock_url.port or 80)
if (
    gemini_url.username
    or gemini_url.password
    or gemini_url.query
    or gemini_url.fragment
    or gemini_origin != wiremock_origin
    or gemini_url.path != "/v1beta/models/gemini-2.5-flash:generateContent"
):
    raise SystemExit("GEMINI_API_URL must use the fixed local WireMock endpoint")

fixture_count = integer("FIXTURE_COUNT", 1)
fixture_retrospectives = integer("PERF_FIXTURE_RETROSPECTIVES", 1)
if fixture_count != fixture_retrospectives:
    raise SystemExit("FIXTURE_COUNT must match PERF_FIXTURE_RETROSPECTIVES")

fixture_version = value("FIXTURE_VERSION")
if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", fixture_version):
    raise SystemExit("FIXTURE_VERSION must be a lowercase version identifier")

fixture_epoch = value("PERF_FIXTURE_EPOCH")
try:
    parsed_epoch = datetime.fromisoformat(fixture_epoch.replace("Z", "+00:00"))
except ValueError as error:
    raise SystemExit("PERF_FIXTURE_EPOCH must be ISO-8601") from error
if parsed_epoch.tzinfo is None:
    raise SystemExit("PERF_FIXTURE_EPOCH must include a timezone")

repetition_index = integer("MEASUREMENT_REPETITION_INDEX", 1)
repetition_total = integer("MEASUREMENT_REPETITION_TOTAL", 1)
if repetition_index > repetition_total:
    raise SystemExit("MEASUREMENT_REPETITION_INDEX cannot exceed MEASUREMENT_REPETITION_TOTAL")

application_dirty = boolean("APPLICATION_GIT_DIRTY")
harness_dirty = boolean("HARNESS_GIT_DIRTY")
allow_dirty = boolean("ALLOW_DIRTY_PERFORMANCE_RUN")
if not allow_dirty and (application_dirty or harness_dirty):
    raise SystemExit(
        "Performance environment is dirty. Commit the harness and use a clean application worktree, "
        "or set ALLOW_DIRTY_PERFORMANCE_RUN=true only for manifest validation."
    )

read_duration = duration("READ_DURATION")
ai_max_duration = duration("AI_MAX_DURATION")
fail_fast_ai_repeat = boolean("FAIL_FAST_AI_REPEAT")
p95_millis = optional_number("P95_MS")
async_core_pool_size = integer("AI_REVIEW_ASYNC_CORE_POOL_SIZE", 1)
async_max_pool_size = integer("AI_REVIEW_ASYNC_MAX_POOL_SIZE", 1)
if async_core_pool_size > async_max_pool_size:
    raise SystemExit("AI_REVIEW_ASYNC_CORE_POOL_SIZE cannot exceed AI_REVIEW_ASYNC_MAX_POOL_SIZE")
if value("READ_RETROSPECTIVE_IDS").strip():
    raise SystemExit("READ_RETROSPECTIVE_IDS must be empty so the fixed fixture determines detail IDs")

rate_limit_ips = {
    "prefix": value("RATE_LIMIT_IP_PREFIX"),
    "signup": value("RATE_LIMIT_SIGNUP_IP"),
    "login": value("RATE_LIMIT_LOGIN_IP"),
    "passwordReset": value("RATE_LIMIT_PASSWORD_RESET_IP"),
}
if rate_limit_ips != {
    "prefix": "10.67",
    "signup": "10.67.1.11",
    "login": "10.67.1.12",
    "passwordReset": "10.67.1.13",
}:
    raise SystemExit("Rate Limit test IPs must match the fixed Phase 0B values")

performance_root_path = pathlib.Path(performance_root).resolve()
harness_files = []
for candidate in performance_root_path.rglob("*"):
    relative = candidate.relative_to(performance_root_path)
    if relative.parts and relative.parts[0] == "results":
        continue
    if candidate.is_symlink():
        raise SystemExit(f"Performance harness must not contain symlinks: {relative}")
    if not candidate.is_file():
        continue
    if (
        candidate.suffix in {".sh", ".js", ".json", ".yml"}
        or candidate.name in {"K6_VERSION", "K6_LINUX_AMD64_SHA256", "env.example"}
    ):
        harness_files.append((relative.as_posix(), candidate))
if not harness_files:
    raise SystemExit("No performance harness assets were found")

harness_digest = hashlib.sha256()
for relative, candidate in sorted(harness_files):
    harness_digest.update(relative.encode("utf-8"))
    harness_digest.update(b"\0")
    harness_digest.update(candidate.read_bytes())
    harness_digest.update(b"\0")
harness_content_sha256 = harness_digest.hexdigest()

manifest = {
    "schemaVersion": 1,
    "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "runId": run_id,
    "source": {
        "applicationBaselineSha": baseline_sha,
        "applicationCommitSha": commit_sha("APPLICATION_COMMIT_SHA"),
        "applicationGitDirty": application_dirty,
        "harnessCommitSha": commit_sha("HARNESS_COMMIT_SHA"),
        "harnessGitDirty": harness_dirty,
        "harnessContentSha256": harness_content_sha256,
    },
    "runtime": {
        "mode": runtime_mode,
        "configuredAppJavaVersion": configured_java_version,
        "detectedAppJavaVersion": detected_java_version,
        "javaDetectionSource": java_detection_source,
        "shellJavaVersion": value("SHELL_JAVA_VERSION"),
        "jvm": {
            "heapInitial": configured_heap_initial,
            "heapMaximum": configured_heap_maximum,
            "garbageCollector": configured_gc,
            "javaToolOptionsValidated": True,
            "ambientJavaOptionsRejected": True,
            "timezone": value("APP_TIMEZONE"),
        },
        "k6ExpectedVersion": value("K6_EXPECTED_VERSION"),
        "k6ActualVersion": value("K6_ACTUAL_VERSION"),
    },
    "application": {
        "springProfilesActive": value("SPRING_PROFILES_ACTIVE"),
        "launchMode": "isolated-env-allowlist",
        "bindingsValidated": {
            "mongo": True,
            "redis": True,
            "gemini": True,
            "composePublishedPorts": True,
            "springApplicationJsonAbsent": True,
        },
        "geminiClient": {
            "connectTimeoutMillis": integer("GEMINI_CONNECT_TIMEOUT_MILLIS", 1),
            "responseTimeoutSeconds": integer("GEMINI_RESPONSE_TIMEOUT_SECONDS", 1),
            "readTimeoutSeconds": integer("GEMINI_READ_TIMEOUT_SECONDS", 1),
            "writeTimeoutSeconds": integer("GEMINI_WRITE_TIMEOUT_SECONDS", 1),
            "maxRetries": integer("GEMINI_MAX_RETRIES"),
            "retryBackoffMillis": integer("GEMINI_RETRY_BACKOFF_MILLIS"),
        },
        "aiReviewExecutor": {
            "corePoolSize": async_core_pool_size,
            "maxPoolSize": async_max_pool_size,
            "queueCapacity": integer("AI_REVIEW_ASYNC_QUEUE_CAPACITY", 1),
        },
    },
    "endpoints": {
        "base": {
            "host": base_url.hostname,
            "port": base_url.port or 80,
        },
        "mongo": {
            "host": mongo.hostname,
            "port": mongo_endpoint_port,
            "database": database_name,
        },
        "redis": {
            "host": redis_host,
            "port": redis_port,
            "database": redis_database,
        },
        "wiremock": {
            "host": wiremock_url.hostname,
            "port": wiremock_endpoint_port,
        },
    },
    "host": {
        "os": value("HOST_OS"),
        "architecture": value("HOST_ARCH"),
        "cpu": value("HOST_CPU"),
        "memoryBytes": value("HOST_MEMORY_BYTES"),
    },
    "containers": images,
    "mock": {
        "mode": mock_mode,
        "fixedDelayMillis": integer("MOCK_GEMINI_DELAY_MS"),
    },
    "fixture": {
        "version": fixture_version,
        "database": database_name,
        "epoch": parsed_epoch.isoformat().replace("+00:00", "Z"),
        "retrospectiveCount": fixture_retrospectives,
        "bojId": value("PERF_BOJ_ID"),
        "aiBojIdPrefix": value("PERF_AI_BOJ_ID_PREFIX"),
        "mongoEnvironment": value("MONGO_ENVIRONMENT"),
        "redisEnvironment": value("REDIS_ENVIRONMENT"),
    },
    "measurement": {
        "repetitionIndex": repetition_index,
        "repetitionTotal": repetition_total,
        "thresholds": {
            "p95Millis": p95_millis,
        },
        "auth": {
            "tokenTtlSeconds": integer("JWT_TTL_SECONDS", 1),
        },
        "read": {
            "vus": integer("READ_VUS", 1),
            "duration": read_duration,
            "weights": {
                "dashboard": integer("READ_DASHBOARD_WEIGHT", 1),
                "statistics": integer("READ_STATISTICS_WEIGHT", 1),
                "retrospectiveList": integer("READ_LOG_LIST_WEIGHT", 1),
                "retrospectiveDetail": integer("READ_LOG_DETAIL_WEIGHT", 1),
            },
            "pageSize": integer("READ_PAGE_SIZE", 1),
            "maxPage": integer("READ_MAX_PAGE", 1),
            "sleepSeconds": number("READ_SLEEP_SECONDS"),
        },
        "ai": {
            "concurrency": integer("AI_CONCURRENCY", 1),
            "repeatCount": integer("AI_REPEAT_COUNT", 1),
            "syncWaitMillis": integer("AI_SYNC_WAIT_MS"),
            "maxDuration": ai_max_duration,
            "pollTimeoutSeconds": integer("AI_POLL_TIMEOUT_SECONDS", 1),
            "pollIntervalMillis": integer("AI_POLL_INTERVAL_MILLIS", 1),
            "failedPollTimeoutSeconds": integer("AI_FAILED_POLL_TIMEOUT_SECONDS", 1),
            "completedPollTimeoutSeconds": integer("AI_COMPLETED_POLL_TIMEOUT_SECONDS", 1),
            "expectedGeminiCalls": integer("EXPECTED_GEMINI_CALLS", 1),
            "failFastRepeat": fail_fast_ai_repeat,
        },
        "rateLimit": {
            "signupMax": integer("RATE_LIMIT_SIGNUP_MAX", 1),
            "loginMax": integer("RATE_LIMIT_LOGIN_MAX", 1),
            "passwordResetMax": integer("RATE_LIMIT_PASSWORD_RESET_MAX", 1),
            "overageRequests": integer("RATE_LIMIT_OVERAGE_REQUESTS", 1),
            "sleepSeconds": number("RATE_LIMIT_SLEEP_SECONDS"),
            "clientIps": rate_limit_ips,
        },
    },
    "redaction": {
        "policy": "explicit-allowlist",
        "secretsRecorded": False,
    },
}

protocol_measurement = dict(manifest["measurement"])
protocol_measurement.pop("repetitionIndex", None)
protocol_canonical = {
    "schemaVersion": 1,
    "harnessContentSha256": manifest["source"]["harnessContentSha256"],
    "runtime": manifest["runtime"],
    "host": manifest["host"],
    "containers": manifest["containers"],
    "mock": manifest["mock"],
    "fixture": manifest["fixture"],
    "endpoints": manifest["endpoints"],
    "application": {
        "springProfilesActive": manifest["application"]["springProfilesActive"],
        "launchMode": manifest["application"]["launchMode"],
        "bindingsValidated": manifest["application"]["bindingsValidated"],
    },
    "measurement": protocol_measurement,
}
protocol_serialized = json.dumps(
    protocol_canonical,
    ensure_ascii=False,
    sort_keys=True,
    separators=(",", ":"),
)
protocol_fingerprint = hashlib.sha256(protocol_serialized.encode("utf-8")).hexdigest()

application_config = {
    "geminiClient": manifest["application"]["geminiClient"],
    "aiReviewExecutor": manifest["application"]["aiReviewExecutor"],
}
application_config_serialized = json.dumps(
    application_config,
    ensure_ascii=False,
    sort_keys=True,
    separators=(",", ":"),
)
application_config_fingerprint = hashlib.sha256(
    application_config_serialized.encode("utf-8")
).hexdigest()

manifest["protocol"] = {
    "id": protocol_id,
    "fingerprintSha256": protocol_fingerprint,
    "applicationConfigSha256": application_config_fingerprint,
    "repetitionIndexExcluded": True,
    "applicationConfigTreatedAsTarget": True,
}

protocol_snapshot = {
    "schemaVersion": 1,
    "protocolId": protocol_id,
    "createdAt": manifest["capturedAt"],
    "fingerprintSha256": protocol_fingerprint,
    "canonical": protocol_canonical,
}


def load_protocol_snapshot(path):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Performance protocol snapshot is unreadable: {path}") from error


protocol_path = os.path.abspath(protocol_path)
protocol_dir = os.path.dirname(protocol_path)
os.makedirs(protocol_dir, exist_ok=True)
if not os.path.exists(protocol_path):
    descriptor, temporary_protocol_path = tempfile.mkstemp(
        prefix=".performance-protocol-",
        dir=protocol_dir,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as destination:
            destination.write(
                json.dumps(protocol_snapshot, ensure_ascii=False, indent=2) + "\n"
            )
        try:
            os.link(temporary_protocol_path, protocol_path)
        except FileExistsError:
            pass
    finally:
        try:
            os.unlink(temporary_protocol_path)
        except FileNotFoundError:
            pass

existing_protocol = load_protocol_snapshot(protocol_path)
existing_protocol_comparable = dict(existing_protocol)
existing_protocol_comparable.pop("createdAt", None)
current_protocol_comparable = dict(protocol_snapshot)
current_protocol_comparable.pop("createdAt", None)
if existing_protocol_comparable != current_protocol_comparable:
    raise SystemExit(
        "Performance protocol differs from the existing paired before/after snapshot. "
        "Restore the fixed conditions or use a new documented PERFORMANCE_PROTOCOL_ID."
    )

serialized = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
for forbidden in ("JWT_SECRET", "PASSWORD", "API_KEY", "TOKEN", "MONGO_URI"):
    if forbidden in serialized:
        raise SystemExit(f"Secret-bearing field leaked into manifest: {forbidden}")

output_path = os.path.abspath(output_path)
output_dir = os.path.dirname(output_path)
os.makedirs(output_dir, exist_ok=True)
if os.path.exists(output_path):
    try:
        with open(output_path, encoding="utf-8") as existing_source:
            existing_manifest = json.load(existing_source)
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Existing environment manifest is unreadable: {output_path}") from error

    current_manifest = dict(manifest)
    existing_comparable = dict(existing_manifest)
    current_manifest.pop("capturedAt", None)
    existing_comparable.pop("capturedAt", None)
    if existing_comparable != current_manifest:
        raise SystemExit(
            "Existing environment manifest does not match the current performance environment. "
            "Use a new K6_RUN_ID."
        )
    print(output_path)
    raise SystemExit(0)

descriptor, temporary_path = tempfile.mkstemp(prefix=".environment-manifest-", dir=output_dir)
try:
    with os.fdopen(descriptor, "w", encoding="utf-8") as destination:
        destination.write(serialized)
    os.replace(temporary_path, output_path)
except Exception:
    try:
        os.unlink(temporary_path)
    except FileNotFoundError:
        pass
    raise

print(output_path)
PY
