# DidimLog Local Performance Verification - 2026-06-21

## Scope

- Local synthetic verification.
- 운영 처리량 또는 최대 성능 주장이 아니다.
- Gemini는 Node local mock으로 격리했고 실제 Gemini를 호출하지 않았다.
- Solved.ac, OAuth Provider, SMTP 서버를 호출하지 않았다.
- 개선율과 AI 비용 절감률은 계산하지 않았다.

## Environment

| Item | Value |
| --- | --- |
| Measurement Target SHA | `677069bb5b31fb87ba9b4d8ee4c72f4d7df375bf` |
| Git Dirty | `false` |
| k6 Version | `v2.0.0` official binary, `commit/8c3be52cc1` |
| App JVM | Java 17.0.14 from `bootRun` log |
| Shell Java | openjdk 21.0.6 |
| Kotlin Plugin | 1.9.25 |
| JVM Heap | NOT_CAPTURED |
| CPU | Apple M5 Pro |
| Memory | 51,539,607,552 bytes |
| MongoDB | local `didimlog-performance`, MongoDB 7.0.16 |
| Redis | local Redis 7.2.5 |
| Mock Mode | `node` |
| Mock Gemini Delay | 500 ms |
| Fixture Count | 100 retrospectives |

## Commands

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew jacocoMergedReport
bash -n performance/k6/run-local.sh
bash -n performance/verify/verify_ai_call_count.sh
node --check performance/mock-external/gemini/node-mock/server.js
find performance/k6 -name '*.js' -print0 | xargs -0 -n1 node --check
docker compose -f performance/mock-external/docker-compose.performance.yml config
k6 inspect performance/k6/smoke.js
k6 inspect performance/k6/read-workload.js
k6 inspect performance/k6/ai-review-concurrency.js
k6 inspect performance/k6/auth-rate-limit.js
performance/k6/run-local.sh seed
performance/k6/run-local.sh preflight
performance/k6/run-local.sh smoke
performance/k6/run-local.sh read
AI_CONCURRENCY=50 AI_REPEAT_COUNT=10 performance/k6/run-local.sh ai-review
performance/k6/run-local.sh ai-retry
performance/k6/run-local.sh rate-limit
performance/k6/run-local.sh cleanup
```

## Read Workload

| Metric | Value |
| --- | ---: |
| VUs | 10 |
| Duration | 1m |
| Requests | 5,431 |
| RPS | 90.40698039769916 |
| HTTP Failure Rate | 0 |
| Check Success Rate | 1 |
| P50 | 9.515 ms |
| P90 | 13.006 ms |
| P95 | 13.894 ms |
| P99 | 15.5371 ms |

| Endpoint | Requests | P50 | P95 | P99 | Success Rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| Dashboard | 1,668 | 9.48 ms | 14.139 ms | 15.7773 ms | 1 |
| Statistics | 1,340 | 9.565 ms | 13.4622 ms | 15.37883 ms | 1 |
| Retrospective List | 1,609 | 9.699 ms | 14.045 ms | 15.90012 ms | 1 |
| Retrospective Detail | 813 | 9.121 ms | 13.6746 ms | 14.84144 ms | 1 |

## AI Concurrency

| Run | Concurrent Requests | Classified Responses | Gemini Calls | Matching Logs | Final Reviews | Duplicate Reviews | Final Status | Lock Present | Unexpected 5xx | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| 1 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 2 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 3 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 4 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 5 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 6 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 7 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 8 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 9 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |
| 10 | 50 | 50 | 1 | 1 | 1 | 0 | COMPLETED | false | 0 | PASS |

## AI Failed Retry

- First call 202: PASS
- Intermediate FAILED: PASS
- Intermediate Lock removed: PASS
- Second call 202: PASS
- Final COMPLETED: PASS
- Final cached 200: PASS
- Gemini Calls: 2
- Final Reviews: 1
- Duplicate Reviews: 0
- Lock Present: false

## Rate Limit

| Endpoint | Allowed | Rejected 429 | Unexpected | Unlock Time | Result |
| --- | ---: | ---: | ---: | --- | --- |
| Signup | 5 | 2 | 0 | valid future ISO-8601 checked | PASS |
| Login | 10 | 2 | 0 | valid future ISO-8601 checked | PASS |
| Reset Password | 3 | 2 | 0 | valid future ISO-8601 checked | PASS |

## Resume-Ready Evidence

동일 로그에 대한 동시 요청 50건을 10회 반복하고, 모든 회차에서 Gemini 호출 1회, AI Review 최종 저장 1건, 중복 저장 0건, 최종 COMPLETED 상태 및 Lock 잔존 0건을 확인했다.

## Not Executed

- WireMock container runtime measurement: NOT_EXECUTED. Docker Compose config는 검증했지만, 로컬 측정은 Node mock 모드로 수행했다.
- 운영 환경 또는 staging 부하: NOT_EXECUTED. 운영/원격 fixture seed와 AI 검증은 금지되어 있다.

## README Check

```bash
git diff HEAD -- README.md
```

Expected and observed: empty output.
