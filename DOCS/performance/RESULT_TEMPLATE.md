# DidimLog Local Performance Verification Template

검증되지 않은 숫자는 작성하지 않는다. 실행하지 못한 항목은 `NOT_EXECUTED`로 남긴다.

## Scope

- Local synthetic verification
- 운영 처리량 또는 최대 성능 주장이 아님
- 외부 Gemini 미호출
- 개선율 미계산

## Environment

- Measurement Target SHA: NOT_EXECUTED
- Git Dirty: NOT_EXECUTED
- k6 Version: NOT_EXECUTED
- Java/Kotlin: NOT_EXECUTED
- JVM Heap: NOT_EXECUTED
- CPU/Memory: NOT_EXECUTED
- MongoDB Version: NOT_EXECUTED
- Redis Version: NOT_EXECUTED
- Mock Mode: NOT_EXECUTED
- Mock Delay: NOT_EXECUTED
- Fixture Count: NOT_EXECUTED

## Actual API Paths

| Area | Path | Verified From |
| --- | --- | --- |
| Dashboard | `GET /api/v1/dashboard` | Controller/Test |
| Statistics | `GET /api/v1/statistics` | Controller/Test |
| Log create | `POST /api/v1/logs` | Controller/Test |
| Log template | `GET /api/v1/logs/{logId}/template` | Controller/Test |
| AI review | `POST /api/v1/logs/{logId}/ai-review` | Controller/Test |
| List workload | `GET /api/v1/retrospectives` | Controller/Test |
| Detail workload | `GET /api/v1/retrospectives/{id}` | Controller/Test |
| Signup RL | `POST /api/v1/auth/signup` | RateLimitInterceptor |
| Login RL | `POST /api/v1/auth/login` | RateLimitInterceptor |
| Reset password RL | `POST /api/v1/auth/reset-password` | RateLimitInterceptor |

## External API Mock

- Gemini mock:
- Gemini request journal:
- Solved.ac isolation:
- OAuth/SMTP exclusion:

## Commands

```bash
NOT_EXECUTED
```

## Read Workload

| Metric | Value |
| --- | --- |
| VUs | NOT_EXECUTED |
| Duration | NOT_EXECUTED |
| Requests | NOT_EXECUTED |
| RPS | NOT_EXECUTED |
| Error rate | NOT_EXECUTED |
| Check success rate | NOT_EXECUTED |

| Endpoint | Requests | P50 | P95 | P99 | Success Rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dashboard | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Statistics | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Retrospective List | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Retrospective Detail | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## AI Concurrency

| Run | Concurrent Requests | Classified Responses | Gemini Calls | Matching Logs | Final Reviews | Duplicate Reviews | Final Status | Lock Present | Unexpected 5xx | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| 1 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 2 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 3 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 4 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 5 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 6 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 7 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 8 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 9 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 10 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## AI Failed Retry

- First call 202: NOT_EXECUTED
- Intermediate FAILED: NOT_EXECUTED
- Intermediate Lock removed: NOT_EXECUTED
- Second call 202: NOT_EXECUTED
- Final COMPLETED: NOT_EXECUTED
- Final cached 200: NOT_EXECUTED
- Gemini Calls: NOT_EXECUTED
- Final Reviews: NOT_EXECUTED
- Duplicate Reviews: NOT_EXECUTED
- Lock Present: NOT_EXECUTED

## Rate Limit

| Endpoint | Allowed | Rejected 429 | Unexpected | Unlock Time | Result |
| --- | ---: | ---: | ---: | --- | --- |
| Signup | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Login | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Reset Password | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## Resume-Ready Evidence

10회 모두 실제 통과한 경우에만 다음 문장을 작성한다.

> 동일 로그에 대한 동시 요청 50건을 10회 반복하고, 모든 회차에서 Gemini 호출 1회, AI Review 최종 저장 1건, 중복 저장 0건, 최종 COMPLETED 상태 및 Lock 잔존 0건을 확인했다.

## Not Executed

- Item:
- Reason:

## README Check

```bash
git diff -- README.md
```

Expected: empty output.
