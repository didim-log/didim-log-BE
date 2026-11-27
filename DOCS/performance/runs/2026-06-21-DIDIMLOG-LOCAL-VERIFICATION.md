# 로컬 동시성·요청 제한 검증 기록 (2026-06-21)

같은 로그에 AI 리뷰를 50건 동시에 요청해도 Gemini 호출과 저장이 1건씩만 일어나는지, 인증 요청 제한이 한도대로 막는지를 로컬에서 확인한 기록입니다. 한 번 측정한 결과라 변경 전 값은 없고, 기대 동작과 실제 결과를 비교합니다.

## 결과 요약

| 항목 | 기대 | 결과 |
| --- | --- | --- |
| 같은 로그 50건 동시 AI 리뷰 (10회) | 회차마다 Gemini 호출 1회, 리뷰 1건 | 10회 모두 호출 1회·리뷰 1건·중복 0건, 최종 `COMPLETED`, 잠금 잔존 0건 |
| AI 리뷰 실패 뒤 재요청 | 실패 상태로 잠금 해제, 재요청 시 완료 | 첫 호출 실패 → 잠금 해제 → 재요청 완료, Gemini 호출 2회·리뷰 1건 |
| 회원가입 요청 제한 | 5건 허용 | 허용 5건, 429 2건 |
| 로그인 요청 제한 | 10건 허용 | 허용 10건, 429 2건 |
| 비밀번호 재설정 요청 제한 | 3건 허용 | 허용 3건, 429 2건 |
| 조회 부하 (10 VUs, 1분) | 오류 없음 | 5,431건, 실패 0건, p95 13.894ms |

429 응답에는 모두 미래 시각의 해제 시각(ISO-8601)이 들어 있었습니다.

## 측정 조건

| 항목 | 값 |
| --- | --- |
| 실행 | `./gradlew bootRun`, Java 17.0.14, JVM 힙 기록 안 함 |
| 저장소 | 로컬 MongoDB 7.0.16, Redis 7.2.5 |
| Gemini | 로컬 Node mock, 응답 지연 500ms |
| 테스트 데이터 | 회고 100건 |
| 부하 도구 | k6 v2.0.0 |
| 장비 | Apple M5 Pro, 메모리 48GiB |

solved.ac, OAuth 제공자, SMTP, 실제 Gemini는 호출하지 않았습니다. 운영 처리량이나 최대 성능이 아니며, 이 기록은 이후 [관리자 회원 조회 최적화](../../refactoring/be-refactor/ADMIN_QUERY_OPTIMIZATION_OVERVIEW.md)의 변경 전후 비교에는 쓰지 않습니다.

## 조회 부하 상세

| API | 요청 | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: |
| 대시보드 | 1,668 | 9.48ms | 14.14ms | 15.78ms |
| 통계 | 1,340 | 9.57ms | 13.46ms | 15.38ms |
| 회고 목록 | 1,609 | 9.70ms | 14.05ms | 15.90ms |
| 회고 상세 | 813 | 9.12ms | 13.67ms | 14.84ms |
| 전체 | 5,431 (초당 90.4건) | 9.52ms | 13.89ms | 15.54ms |

## 확인한 방법

```bash
performance/k6/run-local.sh seed
performance/k6/run-local.sh preflight
performance/k6/run-local.sh read
AI_CONCURRENCY=50 AI_REPEAT_COUNT=10 performance/k6/run-local.sh ai-review
performance/k6/run-local.sh ai-retry
performance/k6/run-local.sh rate-limit
performance/k6/run-local.sh cleanup
```

측정 전에 `./gradlew clean test`, `./gradlew integrationTest`, k6 스크립트 문법 검사, compose 설정 검사를 통과했습니다.

## 남은 한계

- WireMock 컨테이너가 아닌 Node mock으로 측정했습니다.
- JVM 힙을 기록하지 않았고 한 번만 실행해, 변경 전후 비교 기준으로는 쓰지 않습니다.
- 운영·스테이징 부하는 측정하지 않았습니다.
