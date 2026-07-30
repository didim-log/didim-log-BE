# DidimLog k6 Performance Guide

이 문서는 README를 수정하지 않고 DidimLog 조회 API, AI 회고 중복 생성 방지, Redis Rate Limit 정책을 로컬에서 재현하는 절차를 기록한다.

## 실제 API 경로

Controller와 테스트 코드 기준 경로는 다음과 같다.

| 유형 | 실제 경로 | 비고 |
| --- | --- | --- |
| Dashboard 조회 | `GET /api/v1/dashboard` | JWT 필요 |
| Statistics 조회 | `GET /api/v1/statistics` | JWT 필요 |
| 연도별 Heatmap | `GET /api/v1/statistics/heatmap?year=2026` | read-workload 기본 대상은 아님 |
| Log 생성 | `POST /api/v1/logs` | AI 실험용 log 생성 |
| Log template 조회 | `GET /api/v1/logs/{logId}/template` | LogController의 유일한 Log 조회성 API |
| AI 회고 생성/조회 | `POST /api/v1/logs/{logId}/ai-review` | 202 inProgress 또는 200 cached |
| 회고 목록 조회 | `GET /api/v1/retrospectives?page=1&size=10` | 코드상 Log 목록 API가 없어 read-workload의 `log_list` 태그는 이 실제 경로를 사용 |
| 회고 상세 조회 | `GET /api/v1/retrospectives/{retrospectiveId}` | 코드상 Log 상세 API가 없어 read-workload의 `log_detail` 태그는 이 실제 경로를 사용 |
| 회원가입 Rate Limit | `POST /api/v1/auth/signup` | IP key `rate_limit:signup:{ip}`, 5/hour |
| 로그인 Rate Limit | `POST /api/v1/auth/login` | IP key `rate_limit:login:{ip}`, 10/hour |
| 계정 복구 Rate Limit | `POST /api/v1/auth/find-account`, `/find-id`, `/find-password`, `/reset-password` | 공유 IP key `rate_limit:password_reset:{ip}`, 합계 3/hour |

## AI 회고 호출 흐름

`LogController.requestAiReview`는 `AiReviewService.requestOneLineReviewAsync(logId, requesterBojId)`를 호출한다.

1. `LogRepository.findById(logId)`로 로그를 찾는다.
2. 기존 `aiReview` 또는 동일 코드 캐시가 있으면 외부 AI를 호출하지 않고 반환한다.
3. `MongoLogAiReviewLockRepository.tryAcquireLock`이 `_id`, `aiReview == null`, lock 가능 조건으로 `aiReviewStatus=IN_PROGRESS`, `aiReviewLockExpiresAt=now+45s`를 원자적으로 설정한다.
4. lock 획득 실패 시 다시 캐시를 확인하고 없으면 202 `inProgress=true`를 반환한다.
5. lock 획득 성공 시 `aiReviewTaskExecutor`가 `AiApiClient.requestOneLineReview(prompt, timeoutSeconds=12)`를 비동기로 호출한다.
6. 성공 시 `MongoLogAiReviewLockRepository.markCompleted`가 `aiReview`, `aiReviewStatus=COMPLETED`, `aiReviewDurationMillis`를 저장하고 `aiReviewLockExpiresAt`을 unset한다.
7. 실패 또는 timeout 시 `markFailed`가 `aiReviewStatus=FAILED`로 변경하고 lock expiry를 unset한다. FAILED 상태는 다음 요청에서 lock 재획득 가능 조건이다.

Gemini 실제 활성화 조건은 `ai.gemini.api-key`와 `ai.gemini.url`이 모두 존재하는 경우다. 성능 실험은 `GEMINI_API_URL=http://localhost:8090/...`로 로컬 Gemini mock을 사용한다. `MOCK_GEMINI_MODE=auto|wiremock|node`를 지원하며, `auto`는 Docker Compose WireMock을 먼저 확인한 뒤 로컬 환경에서 WireMock만 실패하면 Node mock으로 fallback한다.

## Safety Guardrails

성능 자산은 실행 초기에 `BASE_URL`, `WIREMOCK_URL`, `MONGO_URI`, `REDIS_HOST`를 검증한다.

- 기본 허용 host는 `localhost`, `127.0.0.1`, `::1`, `mongo`, `redis`, `gemini-wiremock`, `host.docker.internal`이다.
- `MONGO_URI`는 credential 없는 `mongodb://.../didimlog-performance`만 허용한다.
- `TARGET_ENVIRONMENT=prod|production`은 항상 차단한다.
- Fixture seed, cleanup, MongoDB 검증은 로컬 DB에서만 실행한다.
- 원격 staging API 부하는 `ALLOW_REMOTE_LOAD_TEST=true`, `TARGET_ENVIRONMENT=staging`, `REMOTE_TARGET_ALLOWLIST` exact host, HTTPS `BASE_URL` 조건을 모두 만족할 때만 허용한다. 이 경우에도 fixture seed와 AI Mongo 검증은 금지된다.
- `.env.performance`, raw result JSON, token/JWT 파일은 Git ignore 대상이다.

## k6 Version

`performance/k6/K6_VERSION`에 고정된 공식 Grafana k6 release만 사용한다. `run-local.sh`는 실행 중인 `k6 version`과 파일 값을 비교하고, `commit/devel` 빌드는 거부한다.

## 로컬 실행

```bash
cp performance/k6/env.example .env.performance

# 1. 로컬 MongoDB, Redis, Mock Gemini 시작 및 지연 설정
performance/k6/run-local.sh start-mocks

# 2. MongoDB fixture 생성
performance/k6/run-local.sh seed

# 3. 앱 실행은 별도 터미널에서 .env.performance의 Spring 환경 변수로 수행
./gradlew bootRun

# 4. Preflight
performance/k6/run-local.sh preflight

# 5. smoke
performance/k6/run-local.sh smoke

# 6. 조회 부하
performance/k6/run-local.sh read

# 7. AI 동시성 50건 x 10회
performance/k6/run-local.sh ai-review

# 8. FAILED 상태 저부하 재시도 검증
performance/k6/run-local.sh ai-retry

# 9. Redis Rate Limit 정책 검증
performance/k6/run-local.sh rate-limit

# 10. Fixture와 테스트 key 정리
performance/k6/run-local.sh cleanup
```

## 외부 API Mock 방식

- Gemini: `performance/mock-external/docker-compose.performance.yml`의 WireMock을 사용한다. Node fallback은 `performance/mock-external/gemini/node-mock/server.js`가 동일한 admin endpoint를 제공한다.
- MongoDB/Redis: 같은 performance compose의 로컬 컨테이너를 사용한다. 운영 DB/Redis를 사용하지 않는다.
- Gemini 지연시간: `MOCK_GEMINI_DELAY_MS`를 `POST /__admin/settings`의 `fixedDelay`로 설정한다.
- Gemini 호출 횟수: `POST /__admin/requests/count`로 `urlPathPattern=/v1beta/models/.*:generateContent`를 조회한다.
- Solved.ac: read/AI 실험은 로그인 없이 로컬 JWT를 사용한다. Rate Limit 실험은 validation 실패 요청만 보내 외부 조회 전 단계에서 끝낸다.
- OAuth/SMTP: 실험 범위에서 제외한다.

## Threshold

기본 threshold:

- `http_req_failed < 1%`
- `checks >= 99%`
- AI 동시성 `ai_unexpected_5xx == 0`
- AI 동시성 `ai_unexpected_error == 0`
- AI 동시성 `ai_initial_request_count == AI_CONCURRENCY`
- AI 동시성 `ai_classified_response_count == AI_CONCURRENCY`
- AI 동시성 `gemini_call_mismatch == 0`

`P95_MS` 환경 변수를 지정한 경우에만 `http_req_duration p(95) < P95_MS`를 추가한다.

## Rate Limit Key Cleanup

`auth-rate-limit.js`는 위조 가능한 전달 헤더를 보내지 않고 `BASE_URL=http://127.0.0.1:8080`의 실제 연결 주소를 사용한다. 정책마다 VU 한 개가 요청 한 건만 보내고, 공통 시작 시각까지 기다린 뒤 500ms 안에 시작했는지 threshold로 확인한다. HTTP 실행은 경계 응답 수와 헤더 계약을 확인하며, Redis 연산의 원자성은 별도 통합 테스트에서 확인한다. `run-local.sh rate-limit`는 실행 전후에 아래 로컬 테스트 key만 삭제한다.

- `rate_limit:signup:{RATE_LIMIT_CLIENT_IP}`
- `rate_limit:login:{RATE_LIMIT_CLIENT_IP}`
- `rate_limit:password_reset:{RATE_LIMIT_CLIENT_IP}`

## 결과 검증

k6 결과만으로 AI 중복 생성 방지를 성공 처리하지 않는다.

```bash
performance/verify/verify_ai_call_count.sh --run-id "$AI_RUN_ID"
```

검증 조건:

- WireMock Gemini 실제 호출 횟수 1회
- MongoDB matching log 1건
- MongoDB 최종 `aiReviewStatus=COMPLETED`
- MongoDB 최종 `aiReview` 저장 1건
- 동일 run 중 중복 AI review 저장 0건
- 완료 후 `aiReviewLockExpiresAt` 제거
- `aiReviewDurationMillis >= 0`
- 최종 review가 비어 있지 않음

FAILED 중간 상태와 최종 상태는 다음처럼 별도 mode로 검증한다.

```bash
performance/verify/verify_ai_call_count.sh \
  --run-id "$AI_RUN_ID" \
  --expect-status FAILED \
  --expect-gemini-calls 1 \
  --expect-review-count 0

performance/verify/verify_ai_call_count.sh \
  --run-id "$AI_RUN_ID" \
  --expect-status COMPLETED \
  --expect-gemini-calls 2 \
  --expect-review-count 1
```

FAILED 재시도 케이스는 `run-local.sh ai-retry`가 첫 요청, FAILED polling, 실제 `GeminiRateLimiter` 최소 간격 polling, 두 번째 요청, COMPLETED polling, 최종 cached 200 확인을 순서대로 실행한다. WireMock/Node mock은 `FORCE_GEMINI_FAILURE_ONCE` 마커가 포함된 요청의 첫 Gemini 호출만 500으로 응답하고, 다음 호출은 성공 응답을 반환한다. 이 케이스의 예상 Gemini 호출 수는 2회다.

AI 10회 반복은 실제 사용자별 일일 제한과 코드 기반 AI 리뷰 캐시를 우회하려고 정책을 변경하지 않는다. 대신 `run-local.sh`가 회차별 JWT subject를 `PERF_AI_BOJ_ID_PREFIX` 기반으로 분리하고, k6가 회차별 고유 코드 주석을 넣어 매 회차를 독립 fixture로 만든다. 기본은 모든 회차를 실행한 뒤 실패 회차를 집계하며, `FAIL_FAST_AI_REPEAT=true`일 때만 중간 중단한다.

## CI Static Validation

PR Check의 `performance-assets` job은 bash 문법, Node 문법, WireMock JSON, Docker Compose config, `k6 inspect`, `git diff --check`를 검증한다. 이 job의 성공은 성능 자산의 정적 유효성을 뜻하며, 로컬 런타임 AI 50 x 10 성공을 의미하지 않는다.

실행할 수 없는 항목은 수치를 쓰지 말고 `NOT_EXECUTED`로 기록한다.
