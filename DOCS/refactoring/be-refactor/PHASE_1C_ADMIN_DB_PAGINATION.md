# Phase 1C — 관리자 회원 조회 DB 페이징

## 목적

[Phase 1A](./PHASE_1A_ADMIN_N_PLUS_ONE.md)의 N+1 제거와
[Phase 1B](./PHASE_1B_RETROSPECTIVE_STUDENT_INDEX.md)의 회고 index 적용에 이어,
관리자 회원 조회가 모든 `Student`를 애플리케이션에 올린 뒤 검색·가입일 필터와
페이지 분할을 수행하던 구조를 MongoDB 조건 조회로 전환한다. 이번 단계의 목표는
애플리케이션에 전달되고 매핑되는 회원 수를 페이지 크기로 제한하는 것이다.

검색·정렬용 index 추가와 latency·처리량 측정은 이번 범위에 포함하지 않는다.

## 변경

Before:

```text
students findAll
→ JVM 검색·가입일 필터
→ JVM subList
→ 현재 페이지 회고 수 aggregation
```

After:

```text
students count(criteria)
→ students find(criteria + sort + skip + limit)
→ 현재 페이지 회고 수 aggregation
```

`StudentRepositoryCustom`과 `StudentRepositoryImpl`이 동일한 `Criteria`를 count와
페이지 조회에 사용한다.

- 검색: `nickname`, `bojId`, `email` 중 하나에 대소문자 무시 literal 부분 일치
- 가입일: 시작일 `00:00:00` 이상, 종료일 `23:59:59.000` 이하
- 정렬: 요청 정렬 뒤 `_id ASC`를 추가해 동점 페이지 순서를 고정
- 빈 결과·범위 밖 페이지: count 이후 페이지 조회와 회고 집계를 생략
- `createdAt`이 없는 기존 문서: 조회 시각을 사용하는 기존 매핑 의미 유지

검색어는 기존과 같이 trim하지 않는다. 정규식 메타문자는
`Pattern.quote`로 escape해 일반 문자로 처리한다. Controller와 API 문서가 지정한
`rating DESC`도 실제 MongoDB 정렬에 반영한다.

## 구현 방식 선택

단일 `$facet`으로 data와 total을 함께 조회하면 학생 명령을 한 번으로 유지할 수
있다. 하지만 현재 API의 page size에는 상한이 없고 `Student`에는 풀이 이력이
포함되므로, 여러 문서를 한 aggregation 결과에 묶으면 MongoDB의 단일 BSON 문서
16MB 제한에 도달할 수 있다.

이번 단계에서는 타입 매핑을 그대로 사용하는 명시적 `count + find`를 선택했다.
그 결과 일반 조회의 학생 명령은 2회이며, count와 content가 서로 다른 시점의
데이터를 볼 수 있는 최종 일관성 한계가 있다.

## 비교 조건

- Before SHA: `7560d06ad3fd5d58b1a289f5f40a900542dd622c`
- After SHA: `c563731f09cd5d88ed2a0f7f55febcfebe7c4221`
- Before·after `gitDirty`: 모두 `false`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- Fixture: 단순 회원 1,000명, 첫 페이지 20명
- 각 SHA를 새 tmpfs MongoDB container에서 5회 반복
- `AfterConvertCallback<Student>`로 실제 매핑된 엔티티 수 측정
- `find`, `aggregate`, `count`, `getMore`를 collection별로 기록

Before 5회와 after 5회는 시나리오별 JSON hash가 각각 동일했다.

## 측정 결과

| 검증 항목 | Before | After | 해석 |
| --- | ---: | ---: | --- |
| 매핑된 `Student` 수 | 1,000 | 20 | 98.0% 감소 |
| 추적한 MongoDB read command | 3 | 3 | 동일 |
| `students getMore` | 1 | 0 | 고정 fixture에서 관측 |
| 응답 content | 20 | 20 | 동일 |
| `totalElements` | 1,000 | 1,000 | 동일 |

98.0%는 `(1,000 - 20) / 1,000 × 100`으로 계산한 애플리케이션 엔티티
매핑 감소율이다. 메모리 사용량, 네트워크 byte, latency 또는 처리량이 98%
개선됐다는 의미가 아니다.

빈 검색 결과는 매핑 수 `1,000 → 0`, 범위 밖 페이지는
`IllegalArgumentException → content 0·totalElements 1,000`으로 확인했다.
두 결과는 제한된 시나리오와 정확성 수정이며 일반 성능 향상률에는 포함하지 않는다.

## Query plan과 비용

실제 `students` index는 `_id_`만 존재한다. 12명 고정 fixture의 endpoint 조건에서
페이지 조회와 count의 실제 명령을 각각 `explain("executionStats")`로 확인했다.

| 항목 | Before | After |
| --- | ---: | ---: |
| 학생 조회 access | `COLLSCAN` | `COLLSCAN` |
| 학생 페이지 조회 docs examined | 12 | 12 |
| 학생 count docs examined | 미실행 | 12 |
| 학생 docs examined 합계 | 12 | 24 |
| 페이지 조회 반환 문서 (page size 10) | 12 | 10 |

DB 검사는 한 번에서 두 번으로 늘었다. 따라서 이번 단계를 query plan, latency,
DB 부하 개선으로 표현하지 않는다. Phase 1A 이전 page size 10의 전체 endpoint
read command와 비교하면 현재는 `11 → 3`, 72.7% 감소지만, Phase 1B 직후의
2회보다는 count 1회가 늘었다.

회고 수 aggregation은 Phase 1B의 covered `IXSCAN (studentId)`을 계속 사용하며
page size 1·5·10에서 회고 document 검사는 모두 0건이었다.

## 정확성 검증

- 닉네임·BOJ ID·이메일의 대소문자 무시 부분 검색
- `.`, `+` 등 정규식 메타문자의 literal 처리
- 공백 검색 생략과 검색어 비-trim 의미 유지
- 검색 OR와 가입일 범위의 AND 결합
- 시작·종료 시각 포함과 기존 종료일 소수 초 동작 유지
- `rating DESC, _id ASC` 동률 안정 페이징
- 필터 후 `totalElements`, 빈 결과, 범위 밖 페이지
- `createdAt`이 없는 기존 MongoDB 문서 호환
- 현재 페이지 회원만 회고 수 집계

`./gradlew clean check --no-daemon` 결과:

- 단위 테스트: 485개, 실패 0
- 통합 테스트: 45개, 실패 0, 스킵 7개
- core-v1: Line 79.49%, Branch 58.08%, Class 90.86%
- full-v1: Line 62.54%, Branch 43.69%, Class 74.94%
- core-v1·full-v1 coverage gate 통과

커버리지 변화는 성능 성과가 아니라 변경 경로의 회귀 검증 범위로만 기록한다.

## 재현

```bash
git worktree add \
  /tmp/didimlog-phase1c-before-7560d06 \
  7560d06ad3fd5d58b1a289f5f40a900542dd622c
(
  cd /tmp/didimlog-phase1c-before-7560d06
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1c-before-7560d06-run-$run_number" \
      performance/query/run-baseline.sh
  done
)

git worktree add \
  /tmp/didimlog-phase1c-after-c563731 \
  c563731f09cd5d88ed2a0f7f55febcfebe7c4221
(
  cd /tmp/didimlog-phase1c-after-c563731
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1c-after-c563731-run-$run_number" \
      performance/query/run-baseline.sh
  done
)
```

원시 JSON:

```text
performance/results/admin-query-phase1c-before-7560d06-run-*/
performance/results/admin-query-phase1c-after-c563731-run-*/
```

`performance/results`는 Git ignore 대상이다.

## 남은 범위

- `rating DESC, _id ASC` 정렬과 가입일 필터의 실제 index 설계·측정
- 부분 문자열 검색에 적합한 검색 전략 검토
- page size 상한 정책
- latency·처리량·heap·network byte의 별도 paired protocol 측정
- 운영 데이터의 index·누락 필드 확인과 배포 검증

EC2 배포와 운영 확인은 이번 로컬 리팩터링 범위에서 제외했다.
