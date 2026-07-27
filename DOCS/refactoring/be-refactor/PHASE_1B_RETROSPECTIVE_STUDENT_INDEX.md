# Phase 1B — 회고 `studentId` 인덱스 보장

## 목적

[Phase 1A](./PHASE_1A_ADMIN_N_PLUS_ONE.md)에서 관리자 회원 목록의 회고 수 조회를
aggregation 1회로 줄였지만, 실제 MongoDB에는 `_id_` index만 있어 회고 collection
전체를 검사했다. 이번 단계는 `studentId` 단일 index를 실제로 생성하고 같은
aggregation이 해당 index를 선택하는지 검증한다.

## 기존 어노테이션만으로 부족했던 이유

`Retrospective.studentId`에는 이미 `@Indexed`가 있었지만
`spring.data.mongodb.auto-index-creation`은 활성화되어 있지 않았다. 따라서 신규
MongoDB에서 애플리케이션을 실행해도 어노테이션만으로 index가 만들어지지 않았다.

전역 자동 생성을 켜면 회고와 다른 document에 선언된 여러 단일·복합 index와 unique
index까지 동시에 생성된다. 이번 범위를 넘는 쓰기 비용 변화와 기존 데이터 충돌
위험을 피하기 위해 `MongoIndexInitializer`가 다음 index 하나만 시작 시
멱등적으로 보장하도록 했다.

```text
collection: retrospectives
name: studentId
key: { studentId: 1 }
unique: false
sparse: false
```

## 비교 조건

- Before SHA: `90c9ffaa2e64c4a3c3ef11955489ad53cdc7dff2`
- After SHA: `2370494e9d7cc9489da7d323cc5c21a34cfdbbbe`
- Before·after `gitDirty`: 모두 `false`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- Fixture: 회원 12명, 회원별 회고 1건
- Aggregation:
  `$match studentId in pageStudentIds → $group studentId → $project`
- Page size: 1, 5, 10
- 각 SHA와 page size 조합을 새 tmpfs MongoDB container에서 5회 반복

Before와 after는 선택된 access stage, index 이름과 key pattern,
`totalDocsExamined`, `totalKeysExamined`를 실제
`explain("executionStats")` 응답에서 기록했다.

## 측정 결과

| Page size | Read command | Before access | After access | Docs examined | Keys examined |
| ---: | ---: | --- | --- | ---: | ---: |
| 1 | `2 → 2` | `COLLSCAN` | `IXSCAN (studentId)` | `12 → 0` | `0 → 1` |
| 5 | `2 → 2` | `COLLSCAN` | `IXSCAN (studentId)` | `12 → 0` | `0 → 5` |
| 10 | `2 → 2` | `COLLSCAN` | `IXSCAN (studentId)` | `12 → 0` | `0 → 10` |

각 page size의 before 5회와 after 5회는 각각 동일한 JSON hash를 기록했다. After의
선택 key pattern은 모두 `{studentId: 1}`이고, group 반환 수는 각각 1·5·10이다.

이 aggregation은 `studentId`만 match·group·project하므로 MongoDB 7.0.16에서
`PROJECTION_COVERED → IXSCAN`으로 실행되어 회고 document 본문을 읽지 않았다.
표의 문서 검사 감소는 이 고정 fixture와 query에 한정된 결과다.

## Phase 1A와 합친 결과

Phase 1A 이전에는 페이지 회원마다 회고를 조회해 page size 10에서 MongoDB read
command가 11회였다. Phase 1A의 batch aggregation으로 2회가 되었고, 이번
Phase 1B에서 그 aggregation의 access plan을 `COLLSCAN`에서 covered `IXSCAN`으로
바꿨다.

- Page size 5 read command: `6 → 2`, 66.7% 감소
- Page size 10 read command: `11 → 2`, 81.8% 감소
- 회고 집계 access: `COLLSCAN → IXSCAN (studentId)`
- 고정 12건 fixture의 회고 document 검사: `12 → 0`

read command 감소율은 latency나 처리량 개선율이 아니다.

## 회귀 검증

- 실제 index metadata가 `_id_`, `studentId` 두 개만 포함
- `studentId`는 `studentId ASC`, non-unique, non-sparse
- 초기화를 반복 실행해도 `studentId`는 한 개만 유지
- 동일한 key의 기존 index는 이름이 달라도 재사용
- 내부 access stage가 `IXSCAN`이고 선택 index 이름과 key pattern이 일치
- Page size 1·5·10에서 `totalDocsExamined=0`
- `totalKeysExamined=1·5·10`
- 관리자 회원 목록의 MongoDB read command는 계속 2회
- 학생 전체 조회는 기존과 동일한 `COLLSCAN`, 12개 document 검사

`./gradlew clean check --no-daemon` 결과:

- 단위 테스트: 483개, 실패 0
- 통합 테스트: 37개, 실패 0, 스킵 7개
- core-v1: Line 79.07%, Branch 56.60%, Class 90.76%
- full-v1: Line 62.26%, Branch 42.87%, Class 74.83%
- core-v1·full-v1 coverage gate 통과

커버리지 변화는 index 성능 성과가 아니라 회귀 검증 범위로만 기록한다.

## 재현

```bash
git worktree add \
  /tmp/didimlog-phase1b-before-90c9ffaa2e64 \
  90c9ffaa2e64c4a3c3ef11955489ad53cdc7dff2
(
  cd /tmp/didimlog-phase1b-before-90c9ffaa2e64
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1b-before-90c9ffaa2e64-run-$run_number" \
      performance/query/run-baseline.sh
  done
)

git worktree add \
  /tmp/didimlog-phase1b-after-2370494e9d7c \
  2370494e9d7cc9489da7d323cc5c21a34cfdbbbe
(
  cd /tmp/didimlog-phase1b-after-2370494e9d7c
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1b-after-2370494e9d7c-run-$run_number" \
      performance/query/run-baseline.sh
  done
)
```

원시 JSON:

```text
performance/results/admin-query-phase1b-before-90c9ffaa2e64-run-*/
performance/results/admin-query-phase1b-after-2370494e9d7c-run-*/
```

`performance/results`는 Git ignore 대상이다.

## 한계와 다음 범위

- 12건 합성 fixture의 실행 계획 검증이며 운영 latency·처리량 측정이 아니다.
- 관리자 회원 조회는 아직 학생 전체 document를 읽은 뒤 검색·날짜 필터·페이징을
  메모리에서 수행한다.
- 운영 반영 전에는 실제 index 목록, 동일 key의 다른 이름 충돌, MongoDB 계정의
  `createIndex` 권한과 collection 크기를 확인해야 한다.
- EC2 배포와 운영 index 생성 확인은 이번 로컬 리팩터링 범위에서 제외했다.

완료: [Phase 1C — 관리자 회원 조회 DB 페이징](./PHASE_1C_ADMIN_DB_PAGINATION.md)에서
학생 검색·정렬·페이징을 MongoDB query로 이동하고 별도의 clean before/after
protocol로 측정했다.
