# Phase 1D — 관리자 회원 정렬 인덱스

## 목적

[Phase 1C](./PHASE_1C_ADMIN_DB_PAGINATION.md)에서 관리자 회원 조회를 DB 페이징으로
전환했지만, 기본 목록의 `rating DESC, _id ASC` 정렬은 `students` collection
전체를 검사하고 blocking `SORT`를 수행했다.

이번 단계는 관리자 조회 서비스 경로가 실행한 page query와 count aggregation을
각각 `explain("executionStats")`로 측정하고, 기본 정렬을 지원하는 index 하나만
멱등적으로 보장한다.

## 변경

Before:

```text
students count: COLLSCAN
students page: COLLSCAN → blocking SORT → skip → limit
```

After:

```text
students count: COLLSCAN
students page: IXSCAN으로 정렬 충족 → offset key skip → page document fetch
```

애플리케이션 시작 시 `MongoIndexInitializer`가 다음 index를 보장한다.

```text
collection: students
name: admin_rating_desc_id_asc
key: { rating: -1, _id: 1 }
unique: false
sparse: false
partial filter: 없음
collation: 기본
hidden: false
```

기존 초기화기는 회고 `studentId` index가 존재하면 함수 전체를 반환했다. 회고와
학생 index 보장을 독립 함수로 분리해 한쪽이 이미 존재하더라도 다른 쪽의 누락을
복구하도록 변경했다. 학생 index는 이름이 아니라 정렬된 key pattern과
non-unique·non-sparse, partial filter 없음, 기본 collation 조건으로 판별하므로,
같은 visible index가 다른 이름으로 존재하면 재사용한다. 같은 조건의 index가
hidden 상태라면 조용히 재사용하거나 운영 설정을 임의로 바꾸지 않고 시작 시
fail-fast한다.

## 범위 선택

관리자 기본 목록은 Controller의 `rating DESC` 요청에 repository가 동률 안정성을
위한 `_id ASC`를 추가한다. 따라서 두 필드와 방향을 그대로 갖는 compound index를
선택했다. 단일 `rating` index는 전체 정렬 조건을 대체하는 것으로 간주하지 않는다.

가입일과 부분 문자열 검색 index는 이번 단계에 추가하지 않았다.

- `createdAt` 선두 index는 날짜 범위를 줄일 수 있지만 기본 `rating` 정렬을
  지원하지 않는다.
- `rating, _id, createdAt` 순서는 기본 정렬은 지원하지만 선택적인 날짜 범위를
  앞에서 제한하지 못한다.
- `createdAt`이 없는 기존 문서를 현재 시각으로 해석하는 조건에서는 날짜 범위에
  따라 `createdAt: null` OR 조건도 추가된다.
- 검색은 `nickname`, `bojId`, `email` 세 필드의 대소문자 무시·비고정 부분
  정규식 OR이므로 일반 B-tree index를 추가하지 않았다.

날짜 필터는 실제 선택도와 호출 비율을 확보한 뒤 별도로 측정하고, 부분 문자열
검색은 Atlas Search·ngram·검색용 정규화 필드 같은 별도 전략으로 검토한다.

## 비교 조건

- Before SHA: `7ef6d833b0775a2765de4e3aa0e945410cdeaccc`
- After SHA: `c80c62fa865ff4117b5c77282814afa4f664e61c`
- Before·after `gitDirty`: 모두 `false`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- Fixture: 단순 회원 1,000명
- 정렬: `rating DESC, _id ASC`
- 첫 페이지: page 0, size 20, skip 0
- 깊은 페이지: page 25, size 20, skip 500
- 각 SHA를 새 tmpfs MongoDB container에서 5회 반복
- 관리자 조회 서비스 경로가 실제 발행한 find command와 count pipeline을 캡처한
  뒤 hint 없이 `explain("executionStats")` 실행

각 그룹의 7개 snapshot은 파일별로 5회 hash가 모두 동일했다. Before·after의
응답 크기, `totalElements`, 첫·마지막 회원 ID, 엔티티 매핑 수, MongoDB read
command와 실제 command 형태도 동일했다.

## 측정 결과

| 시나리오 | Page access | Blocking `SORT` | Page docs examined | Page keys examined | Count docs examined | Page + count docs examined | Read command |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 첫 페이지 Before | `COLLSCAN` | 있음 | 1,000 | 0 | 1,000 | 2,000 | 3 |
| 첫 페이지 After | `IXSCAN` | 없음 | 20 | 20 | 1,000 | 1,020 | 3 |
| 깊은 페이지 Before | `COLLSCAN` | 있음 | 1,000 | 0 | 1,000 | 2,000 | 3 |
| 깊은 페이지 After | `IXSCAN` | 없음 | 20 | 520 | 1,000 | 1,020 | 3 |

- Page document 검사는 `1,000 → 20`, 98.0% 감소했다.
- Page와 count의 `totalDocsExamined` 합은 `2,000 → 1,020`, 49.0% 감소했다.
- Count는 계속 `COLLSCAN`으로 1,000개 document를 검사한다.
- MongoDB read command는 3회로 변하지 않았다.
- 깊은 페이지는 document 검사는 20개지만 skip 500 때문에 index key를 520개
  검사한다.

49.0%는 관리자 조회 서비스 경로의 실제 명령을 각각 explain한 `students` page와
count의 `totalDocsExamined`를 합산한 고정 fixture 수치다. 서로 다른 실행계획의
`docsExamined`와 `keysExamined`를 합치지 않았으며, latency·처리량·CPU·전체 DB
비용이 49.0% 개선됐다는 의미가 아니다.

## 회귀 검증

- 실제 index metadata가 `_id_`, `admin_rating_desc_id_asc`를 포함
- 대상 index가 `rating DESC, _id ASC`, non-unique, non-sparse, partial filter 없음,
  기본 collation, visible
- 초기화를 반복 실행해도 대상 index는 한 개만 유지
- 동일 key pattern의 다른 이름 index 재사용
- 회고 index가 이미 있어도 누락된 학생 index 복구
- 단일 `rating` index가 compound index 생성을 막지 않음
- Partial·별도 collation index가 기본 정렬 index를 대체하지 않음
- 같은 조건의 hidden index를 발견하면 명시적으로 fail-fast
- Page size 1·5·10에서 대상 index 선택과 blocking `SORT` 제거
- 1,000명 fixture의 첫 페이지와 skip 500에서 응답·정렬·명령 수 유지
- 회고 수 aggregation의 covered `IXSCAN (studentId)` 유지

`./gradlew clean check --no-daemon` 결과:

- 단위 테스트: 485개, 실패 0
- 통합 테스트: 53개, 실패 0, 스킵 7개
- core-v1: Line 79.62%, Branch 58.36%, Class 90.86%
- full-v1: Line 62.65%, Branch 43.97%, Class 74.94%
- core-v1·full-v1 coverage gate 통과

커버리지는 성능 성과가 아니라 변경 경로의 회귀 검증 스냅샷으로만 기록한다.

## 재현

```bash
git worktree add \
  /tmp/didimlog-phase1d-before-7ef6d83 \
  7ef6d833b0775a2765de4e3aa0e945410cdeaccc
(
  cd /tmp/didimlog-phase1d-before-7ef6d83
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1d-before-7ef6d83-run-$run_number" \
      performance/query/run-baseline.sh
  done
)

git worktree add \
  /tmp/didimlog-phase1d-after-c80c62f \
  c80c62fa865ff4117b5c77282814afa4f664e61c
(
  cd /tmp/didimlog-phase1d-after-c80c62f
  for run_number in 1 2 3 4 5; do
    ADMIN_QUERY_BASELINE_RUN_ID="admin-query-phase1d-after-c80c62f-run-$run_number" \
      performance/query/run-baseline.sh
  done
)
```

원시 JSON:

```text
performance/results/admin-query-phase1d-before-7ef6d83-run-*/
performance/results/admin-query-phase1d-after-c80c62f-run-*/
```

`performance/results`는 Git ignore 대상이다.

## 한계와 다음 범위

- 합성 1,000명 fixture의 실행계획 검증이며 운영 latency·처리량 측정이 아니다.
- Count의 `COLLSCAN`과 offset 기반 깊은 페이징 비용은 남아 있다.
- Index는 저장 공간과 회원 rating 갱신의 쓰기 비용을 추가한다.
- 데이터가 큰 운영 collection에서는 시작 시 index 생성 시간과 자원 사용량을
  별도로 확인해야 한다.
- 같은 조건의 hidden index는 애플리케이션이 임의로 unhide하지 않으며, 운영자가
  상태를 확인할 수 있도록 시작을 실패시킨다.
- 가입일 filter index, 부분 문자열 검색 전략, page size 상한 정책은 후속 범위다.
- EC2 배포와 운영 index 생성 확인은 이번 로컬 리팩터링 범위에서 제외했다.
