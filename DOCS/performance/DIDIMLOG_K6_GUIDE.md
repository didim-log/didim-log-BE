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
| 비밀번호 재설정 Rate Limit | `POST /api/v1/auth/reset-password` | IP key `rate_limit:password_reset:{ip}`, 3/hour |

## AI 회고 호출 흐름

`LogController.requestAiReview`는 `AiReviewService.requestOneLineReviewAsync(logId, requesterBojId)`를 호출한다.

1. `LogRepository.findById(logId)`로 로그를 찾는다.
2. 기존 `aiReview` 또는 동일 코드 캐시가 있으면 외부 AI를 호출하지 않고 반환한다.
3. `MongoLogAiReviewLockRepository.tryAcquireLock`이 `_id`, `aiReview == null`, lock 가능 조건으로 `aiReviewStatus=IN_PROGRESS`, `aiReviewLockExpiresAt=now+45s`를 원자적으로 설정한다.
4. lock 획득 실패 시 다시 캐시를 확인하고 없으면 202 `inProgress=true`를 반환한다.
5. lock 획득 성공 시 `aiReviewTaskExecutor`가 `AiApiClient.requestOneLineReview(prompt, timeoutSeconds=12)`를 비동기로 호출한다.
6. 성공 시 `MongoLogAiReviewLockRepository.markCompleted`가 `aiReview`, `aiReviewStatus=COMPLETED`, `aiReviewDurationMillis`를 저장하고 `aiReviewLockExpiresAt`을 unset한다.
7. 실패 또는 timeout 시 `markFailed`가 `aiReviewStatus=FAILED`로 변경하고 lock expiry를 unset한다. FAILED 상태는 다음 요청에서 lock 재획득 가능 조건이다.

Gemini 실제 활성화 조건은 `ai.gemini.api-key`와 `ai.gemini.url`이 모두 존재하는 경우다. 성능 실험은 `GEMINI_API_URL=http://localhost:8090/...`로 로컬 Gemini mock을 사용한다. Docker가 있으면 WireMock compose를 사용하고, Docker가 없으면 `run-local.sh start-mocks`가 Node 기반 mock을 띄운다.

## 로컬 실행

```bash
cp performance/k6/env.example .env.performance

# 1. 로컬 MongoDB, Redis, Mock Gemini 시작 및 지연 설정
performance/k6/run-local.sh start-mocks

# 2. MongoDB fixture 생성
performance/k6/run-local.sh seed

# 3. 앱 실행은 별도 터미널에서 .env.performance의 Spring 환경 변수로 수행
./gradlew bootRun

# 4. smoke
performance/k6/run-local.sh smoke

# 5. 조회 부하
performance/k6/run-local.sh read

# 6. AI 동시성 50건 x 10회
performance/k6/run-local.sh ai-review

# 7. FAILED 상태 저부하 재시도 검증
performance/k6/run-local.sh ai-retry

# 8. Redis Rate Limit 정책 검증
performance/k6/run-local.sh rate-limit
```

## 외부 API Mock 방식

- Gemini: `performance/mock-external/docker-compose.performance.yml`의 WireMock을 사용한다. Docker가 없는 환경에서는 동일한 admin endpoint를 제공하는 Node 기반 로컬 mock을 사용한다.
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
- AI 동시성 `gemini_call_mismatch == 0`

`P95_MS` 환경 변수를 지정한 경우에만 `http_req_duration p(95) < P95_MS`를 추가한다.

## Rate Limit Key Cleanup

`auth-rate-limit.js`는 `RATE_LIMIT_SIGNUP_IP`, `RATE_LIMIT_LOGIN_IP`, `RATE_LIMIT_PASSWORD_RESET_IP`로 고정한 테스트 IP만 사용한다. `run-local.sh rate-limit`는 실행 전후에 아래 테스트 key만 삭제한다.

- `rate_limit:signup:{RATE_LIMIT_SIGNUP_IP}`
- `rate_limit:login:{RATE_LIMIT_LOGIN_IP}`
- `rate_limit:password_reset:{RATE_LIMIT_PASSWORD_RESET_IP}`

## 결과 검증

k6 결과만으로 AI 중복 생성 방지를 성공 처리하지 않는다.

```bash
performance/verify/verify_ai_call_count.sh --run-id "$AI_RUN_ID"
```

검증 조건:

- WireMock Gemini 실제 호출 횟수 1회
- MongoDB 최종 `aiReview` 저장 1건
- 동일 run 중 중복 AI review 저장 0건
- 완료 후 `aiReviewLockExpiresAt` 제거

FAILED 재시도 케이스는 `AI_EXPERIMENT=failed-retry`로 같은 k6 파일을 실행한다. WireMock scenario가 `FORCE_GEMINI_FAILURE_ONCE` 마커가 포함된 요청의 첫 Gemini 호출만 500으로 응답하고, 다음 호출은 성공 응답을 반환한다. 이 케이스의 예상 Gemini 호출 수는 2회다.

AI 10회 반복은 실제 사용자별 일일 제한과 코드 기반 AI 리뷰 캐시를 우회하려고 정책을 변경하지 않는다. 대신 `run-local.sh`가 회차별 JWT subject를 `PERF_AI_BOJ_ID_PREFIX` 기반으로 분리하고, k6가 회차별 고유 코드 주석을 넣어 매 회차를 독립 fixture로 만든다. FAILED 재시도 케이스는 실제 `GeminiRateLimiter`의 4초 최소 간격을 존중하기 위해 기본 `AI_FAILED_RETRY_WAIT_SECONDS=5`를 사용한다.

실행할 수 없는 항목은 수치를 쓰지 말고 `NOT_EXECUTED`로 기록한다.
