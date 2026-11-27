# 리팩터링 전 기준선 측정 결과

리팩터링 전에 크롤러와 관리자 회원 조회가 MongoDB·Redis 명령을 몇 번 보내는지, 어떤 실행 계획을 쓰는지 고정한 측정입니다. 이후 개선 문서는 모두 이 값과 같은 조건으로 비교합니다.

## 기준선과 이후 결과

| 항목 | 기준선 | 이후 결과 |
| --- | --- | --- |
| 관리자 회원 목록 MongoDB 조회 (10명 페이지) | 11회 (회원 1 + 회원마다 회고 1) | 2회, [N+1 제거](./PHASE_1A_ADMIN_N_PLUS_ONE.md) |
| 회고 수 조회 실행 계획 | `COLLSCAN`, 문서 12개 검사 | `IXSCAN`, 문서 0개 검사, [회고 인덱스](./PHASE_1B_RETROSPECTIVE_STUDENT_INDEX.md) |
| 회원 1,000명 중 20명 페이지 | 1,000명을 모두 읽고 메모리에서 분할 | 20명만 읽음, [DB 페이징](./PHASE_1C_ADMIN_DB_PAGINATION.md) |
| 크롤러 문제 6건 수집 | MongoDB `find` 6·`update` 6, solved.ac 호출 6 | `find` 0·`update` 6, [메타데이터 부분 갱신](./PHASE_4B_CRAWLER_METADATA_UPSERT.md) |

## 측정 조건

[성능 측정 환경](./PHASE_0B_PERFORMANCE_ENVIRONMENT.md)에서 고정한 MongoDB 7.0.16, Redis 7.2.5, 결정적 solved.ac fixture를 썼습니다. 측정한 코드와 하네스에는 커밋되지 않은 변경이 없었습니다.

## 크롤러 명령 수

실제 MongoDB·Redis에서 문제 6건의 메타데이터 수집을 처음 실행(cold)과 재실행(warm)으로 각각 5회 돌렸습니다.

| 명령 | 5회 모두 |
| --- | --- |
| MongoDB | `find` 6, `update` 6 |
| Redis | `get` 15, `setex` 9, `zadd` 9 |
| solved.ac fixture 호출 | 6 |

문제 수만큼 MongoDB 조회·갱신과 solved.ac 호출이 반복되는 구조입니다. 운영의 요청 간격(메타데이터 500ms, 상세 2~4초)은 그대로 두고 측정에서만 대기를 없앴기 때문에, 이 값은 처리 속도가 아니라 명령 수와 저장 정확도의 기준입니다. cold 실행의 제목·레벨·분류·태그 저장과, warm 실행에서 기존 상세 내용이 보존되는 것도 함께 확인했습니다.

## 관리자 회원 조회

회원 12명과 회원별 회고 1건을 넣고 페이지 크기를 바꿔 측정했습니다.

| 페이지 크기 | MongoDB `find` |
| ---: | ---: |
| 1 | 2 |
| 5 | 6 |
| 10 | 11 |

조회 횟수가 `1 + 페이지의 회원 수`로 늘어나는 N+1입니다. 두 컬렉션 모두 `_id` 외 인덱스가 없어, 특정 학생의 회고 1건을 찾을 때도 문서 12개를 모두 검사했습니다(`COLLSCAN`).

## 확인한 방법

```bash
CRAWLER_BASELINE_RUN_ID=crawler-baseline-01 performance/crawler/run-baseline.sh
ADMIN_QUERY_BASELINE_RUN_ID=admin-query-baseline-01 performance/query/run-baseline.sh
```

- MongoDB 명령 수는 대상 컬렉션으로 제한한 command listener, Redis 명령 수는 `INFO commandstats` 전후 차이, 실행 계획은 `explain("executionStats")`로 셌습니다.
- `./gradlew clean check`가 통과했습니다(단위 482개, 통합 32개 중 25개 통과·조건부 7개 제외).

## 남은 한계

- 이 단계에서는 k6 지연 시간을 재지 않았습니다.
- 원본 결과 JSON은 Git에 올리지 않습니다.
