# Phase 1A — 관리자 회원 조회 N+1 제거

## 목적

관리자 회원 목록에서 현재 페이지의 회원마다 회고 목록을 다시 조회하던 N+1을
제거한다. 이번 단계는 MongoDB read command 수만 개선 대상으로 삼으며,
`studentId` index와 회원 검색·페이징 쿼리 자체의 최적화는 다음 단계로 분리한다.

## 비교 조건

- Before SHA: `8f65f9638b5b9c162785ec99f605c375b031fbb9`
- After SHA: `9d8dc167d7cdafc359e1818741ab57441a77beb6`
- Before·after `gitDirty`: 모두 `false`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- Fixture: 회원 12명, 회원별 회고 1건
- 실제 index: `students`, `retrospectives` 모두 `_id_`만 존재
- Page size: 1, 5, 10

Before와 after는 각 SHA의 `performance/query/run-baseline.sh`와 같은 격리 MongoDB
조건을 사용했다. After 계측은 `find`와 `aggregate`를 모두 read command로 세어
aggregation 전환으로 호출이 숨겨지지 않도록 했다. Before 구현에는 aggregate
호출이 없으므로 기존 `totalFind`가 전체 Mongo read command와 같다.

## 변경

Before는 현재 페이지의 회원마다 다음 조회를 반복했다.

```kotlin
retrospectiveRepository.findAllByStudentId(studentId).size
```

After는 현재 페이지의 non-null 회원 ID를 모아 한 번의 aggregation으로 집계한다.

```text
$match studentId in pageStudentIds
→ $group by studentId
→ $sum retrospectiveCount
```

집계 결과는 `studentId` Map으로 변환해 원래 회원 순서에 결합한다. 회고가 없거나
ID가 없는 회원은 기존과 같이 `retrospectiveCount=0`이며, 빈 페이지에서는
aggregation을 실행하지 않는다.

## 측정 결과

| Page size | Before read command | After read command | 감소율 |
| ---: | ---: | ---: | ---: |
| 1 | 2 (`find 1 + find 1`) | 2 (`find 1 + aggregate 1`) | 0.0% |
| 5 | 6 (`find 1 + find 5`) | 2 (`find 1 + aggregate 1`) | 66.7% |
| 10 | 11 (`find 1 + find 10`) | 2 (`find 1 + aggregate 1`) | 81.8% |

감소율은 `(before - after) / before × 100`으로 계산했다. 이는 MongoDB read command
감소율이며 latency나 처리량 개선율이 아니다.

빈 검색 결과는 회원 `find 1회`, 회고 `aggregate 0회`로 확인했다.

## 정확성 검증

- 회고가 0건·1건·2건인 회원을 섞어 ID별 집계값 검증
- 페이지 밖 회원의 회고가 결과에서 제외되는지 검증
- aggregation 결과 순서와 무관하게 기존 회원 응답 순서 유지
- 집계 결과에 없는 회원은 회고 수 0
- 빈 ID 집합은 MongoDB 호출 없이 빈 Map 반환
- 기존 단건 `findAllByStudentId` 호출 0회

## Query plan

After aggregation의 실제 `$match + $group` pipeline을
`explain("executionStats")`로 측정했다.

- Access plan: `COLLSCAN`
- `totalDocsExamined=12`
- `totalKeysExamined=0`
- Page size 1/5/10의 group 반환 수: 1/5/10

N+1 command는 제거됐지만 `studentId` index가 없어 한 번의 aggregation도 전체
회고 collection을 검사한다. 이 결과가 Phase 1B index 튜닝의 기준선이다.

## 테스트·커버리지 확인

`./gradlew clean check --no-daemon`을 별도 MongoDB·Redis에서 실행했다.

- 단위 테스트: 482개 → 483개
- 통합 테스트: 32개 → 34개, 스킵 7개 유지
- core-v1: Line 78.79% → 78.98%, Branch 55.52% → 56.49%
- full-v1: Line 61.94% → 62.19%, Branch 42.20% → 42.78%
- core-v1·full-v1 Class gate 유지

작은 커버리지 변화는 별도 성과로 주장하지 않고, N+1 변경 경로의 회귀 테스트가
추가됐다는 검증으로만 기록한다.

## 재현

```bash
git worktree add \
  /tmp/didimlog-phase1a-before-8f65f9638b5b \
  8f65f9638b5b9c162785ec99f605c375b031fbb9
(
  cd /tmp/didimlog-phase1a-before-8f65f9638b5b
  ADMIN_QUERY_BASELINE_RUN_ID=admin-query-phase1a-before-8f65f9638b5b \
    performance/query/run-baseline.sh
)

git worktree add \
  /tmp/didimlog-phase1a-after-9d8dc167d7cd \
  9d8dc167d7cdafc359e1818741ab57441a77beb6
(
  cd /tmp/didimlog-phase1a-after-9d8dc167d7cd
  ADMIN_QUERY_BASELINE_RUN_ID=admin-query-phase1a-after-9d8dc167d7cd \
    performance/query/run-baseline.sh
)
```

원시 JSON:

```text
performance/results/admin-query-phase1a-before-8f65f9638b5b/
performance/results/admin-query-phase1a-after-9d8dc167d7cd/
```

`performance/results`는 Git ignore 대상이다.

## 남은 범위

- 완료: [Phase 1B — 회고 `studentId` 인덱스 보장](./PHASE_1B_RETROSPECTIVE_STUDENT_INDEX.md)
- 별도 단계: 전체 회원 `findAll`, in-memory 검색·날짜 필터·페이징 개선
- 별도 단계: latency와 처리량을 고정된 k6 protocol로 반복 측정

README에는 Phase 1A·1B의 command와 query plan 개선을 합친 핵심 지표와 상세 문서
링크만 반영했다.
