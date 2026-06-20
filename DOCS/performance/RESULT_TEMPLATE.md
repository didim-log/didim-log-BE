# DidimLog Performance Result Template

검증되지 않은 숫자는 작성하지 않는다. 실행하지 못한 항목은 `NOT_EXECUTED`로 남긴다.

## 1. Existing State Analysis

- Commit SHA:
- Java/Kotlin:
- JVM Heap:
- CPU/Memory:
- MongoDB environment:
- Redis environment:
- Fixture count:
- Mock Gemini delay:
- Existing performance files:

## 2. Actual API Paths

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

## 3. External API Mock

- Gemini mock:
- Gemini request journal:
- Solved.ac isolation:
- OAuth/SMTP exclusion:

## 4. Commands

```bash
NOT_EXECUTED
```

## 5. Read Workload Result

| Metric | Value |
| --- | --- |
| VU/arrival rate | NOT_EXECUTED |
| Duration | NOT_EXECUTED |
| Requests | NOT_EXECUTED |
| RPS | NOT_EXECUTED |
| Error rate | NOT_EXECUTED |
| Check success rate | NOT_EXECUTED |
| P50/P90/P95/P99 | NOT_EXECUTED |

## 6. AI Concurrency Result

| Iteration | Gemini Calls | Final AI Reviews | Duplicate Reviews | Unexpected 5xx | Result |
| --- | --- | --- | --- | --- | --- |
| 1 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 2 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 3 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 4 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 5 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 6 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 7 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 8 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 9 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| 10 | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## 6-1. AI Failed Retry Result

| Gemini Calls | Final AI Reviews | Duplicate Reviews | Lock Removed | Result |
| --- | --- | --- | --- | --- |
| NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## 7. Rate Limit Result

| Endpoint | Policy | Within-limit responses | 429 responses | Retry/unlock info | Result |
| --- | --- | --- | --- | --- | --- |
| Signup | 5/hour/IP | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Login | 10/hour/IP | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Reset password | 3/hour/IP | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

## 8. Resume-Ready Verified Numbers

- NOT_EXECUTED

## 9. Not Executed

- Item:
- Reason:

## 10. README Check

```bash
git diff -- README.md
```

Expected: empty output.
