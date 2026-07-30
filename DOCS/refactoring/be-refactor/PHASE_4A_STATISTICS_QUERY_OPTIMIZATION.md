# Phase 4A — 통계 조회 데이터 축소

## 문제

`GET /api/v1/statistics`는 집계에 쓰지 않는 회고 본문까지 전체 문서로 조회했다.
회고 본문은 한 건에 최대 5,000자이므로 회고 수가 늘수록 MongoDB에서 애플리케이션으로
넘기는 데이터와 객체 생성 비용도 함께 커졌다.

연도별 히트맵은 학생의 전체 회고를 조회한 뒤 애플리케이션에서 해당 연도만
골랐다. `Retrospective`에 `(studentId, createdAt)` 인덱스 선언은 있었지만 자동
인덱스 생성이 꺼져 있어 실제 시작 경로에서는 생성을 보장하지 않았다.

## 변경

### 전체 통계 projection

전체 통계가 사용하는 필드는 다음 네 개다.

```text
problemId
createdAt
solutionResult
solvedCategory
```

Repository가 위 필드만 `RetrospectiveStatisticsView`로 반환하도록 바꿨다.
전체 회고 수, 실패 수, 카테고리 분포와 최근 365일 히트맵의 계산식은 유지했다.

### 연도 범위 조회

연도별 히트맵은 날짜 필터를 MongoDB query로 옮겼다.

```text
studentId = 요청 사용자
createdAt >= 연도 시작 00:00
createdAt <  다음 범위 시작 00:00
```

- 과거 연도: 다음 해 1월 1일 00:00 미만
- 현재 연도: 내일 00:00 미만
- 미래 연도: 학생 존재를 먼저 확인한 뒤 빈 목록 반환

끝 시각을 배타 조건으로 두어 12월 31일의 소수점 이하 시각도 빠뜨리지 않는다.
연도별 조회 projection은 `problemId`, `createdAt` 두 필드만 포함한다.

### 복합 인덱스

`MongoIndexInitializer`가 다음 인덱스를 멱등적으로 보장한다.

```text
collection: retrospectives
name: idx_retro_student_created
key: { studentId: 1, createdAt: -1 }
unique: false
sparse: false
partial filter: 없음
collation: 기본
hidden: false
```

기존 단일 `studentId` 인덱스는 다른 조회 경로의 회귀를 피하기 위해 유지했다.

## 비교 조건

- Before SHA: `bcfeef4a02a6a0890b6acd56f7dc18802c4cfd50`
- After SHA: `5e60a76961804ee5c2c4ee9ab6597d95112f9425`
- Before·After `gitDirty`: 모두 `false`
- Harness SHA-256:
  `f94075011ed03427f72a933c21310c8b82871561e7f24ddc7818f74bdb1881ba`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- 대상 학생 회고 1,200건, 다른 학생 회고 120건
- 회고 본문 한 건당 4,096자
- 대상 데이터 범위: 2022-01-01부터 2025-04-14
- 연도별 조회: 2024년 366건
- 시간대: `Asia/Seoul`
- 각 SHA를 새 tmpfs MongoDB container에서 5회 반복

계측 코드는 public `StatisticsService`를 호출해 실제 `find` filter와 projection을
캡처했다. 같은 filter와 projection을 `RawBsonDocument`로 다시 실행해 선택된
문서의 BSON 크기를 합산하고, 캡처한 query를
`explain("executionStats")`로 확인했다.

기능 결과는 날짜와 문제 ID, 카테고리 순서를 정규화한 뒤 SHA-256으로 비교했다.
Before와 After의 기능 결과 hash는 시나리오별로 같았다.

```text
전체 통계:
0780fb566cd841862f39de61bde25b7220c2a4045b06de4b881b89c9bd8e0142

2024년 히트맵:
5c4a8a13bdf742282c6f0a0895bfec1c607ad259cfe6f86b190279a70206b082
```

각 그룹의 JSON 파일도 5회 모두 같은 hash를 냈다.

| 구분 | 전체 통계 | 2024년 히트맵 |
| --- | --- | --- |
| Before JSON SHA-256 | `71a50c52aaf37e2e944ac867f0eed1065859b9bdc475e7129f0ce5939ce490fa` | `99cc1fa0589e7d6cb5de420671b4abcd5e95e6fe9d9a30645dfcff0ed2cd5685` |
| After JSON SHA-256 | `6921a3789a8f231b7b58befb5eba21d43a3d9b2611b54b725c7cc68fcdaab8b4` | `bf4ef9cd9fd391ba01e252ae41622fbfa19cb4160efc2cae5b7263359b26ac35` |

## 측정 결과

| 시나리오 | 항목 | Before | After | 변화 |
| --- | --- | ---: | ---: | ---: |
| 전체 통계 | 반환 회고 문서 | 1,200건 | 1,200건 | 동일 |
| 전체 통계 | 반환 회고 BSON | 5,230,680 B | 107,790 B | 97.94% 감소 |
| 전체 통계 | `totalDocsExamined` | 1,200 | 1,200 | 동일 |
| 전체 통계 | 선택 인덱스 | `studentId` | `studentId` | 동일 |
| 2024년 히트맵 | 반환 회고 문서 | 1,200건 | 366건 | 69.50% 감소 |
| 2024년 히트맵 | 반환 회고 BSON | 5,230,680 B | 18,396 B | 99.65% 감소 |
| 2024년 히트맵 | `totalDocsExamined` | 1,200 | 366 | 69.50% 감소 |
| 2024년 히트맵 | `totalKeysExamined` | 1,200 | 366 | 69.50% 감소 |
| 2024년 히트맵 | 선택 인덱스 | `studentId` | `idx_retro_student_created` | 날짜 범위 사용 |

두 시나리오 모두 회고 `find + getMore`는 2회로 같았다. 전체 통계는 모든 회고의
집계값이 필요하므로 검사 문서 수가 줄지 않는다. 이 경로의 개선 대상은 반환 문서의
필드와 BSON 크기다.

97.94%와 99.65%는 고정 fixture에서 선택된 회고 문서 BSON 합계의 감소율이다.
MongoDB wire protocol, 압축, TLS, JVM 메모리, endpoint 지연 시간이나 운영 처리량의
개선율을 뜻하지 않는다. 69.50%는 2024년 히트맵 query의
`totalDocsExamined` 감소율이다.

## 검증

- 전체 통계 결과, 최근 365일 범위와 중복 문제 ID 집계
- `SUCCESS`, `FAIL`, `TIME_OVER`, null 결과
- 쉼표로 구분한 카테고리와 빈 태그
- 고유 성공 문제 수, 평균 풀이 시간과 성공률 반올림
- 빈 풀이·회고 결과
- 과거·현재·미래 연도 범위와 학생 존재 확인 순서
- 실제 MongoDB projection의 nullable 필드 매핑
- 시작 시각 포함, 종료 시각 제외와 다른 학생 데이터 제외
- 복합 인덱스 key 순서·옵션과 반복 초기화
- Before·After public service 결과 hash 일치

전체 검증 결과는 다음과 같다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 680개 통과 |
| 통합 테스트 | 159개 중 151개 통과, 8개 조건부 제외 |
| core-v1 Line / Branch / Class | 85.24% / 63.67% / 93.04% |
| full-v1 Line / Branch / Class | 71.60% / 53.86% / 80.00% |

Phase 3E 최종값과 비교하면 `full-v1`은 Line `70.20% → 71.60%`,
Branch `52.31% → 53.86%`, Class `78.74% → 80.00%`로 늘었다. 기존에
Controller happy path만 있던 통계 경로에 service 특성화 테스트 5개와 실제 MongoDB
projection·범위 테스트 2개를 추가한 결과다. 이 수치는 회귀 검증 범위의 변화이며
성능 개선율에 포함하지 않는다.

조건부 제외 8개 중 1개는 이번 단계의 통계 기준선 테스트다. 일반 `check`에서는
제외하고 `performance/statistics/run-baseline.sh`가 별도 MongoDB container에서
활성화한다.

## 재현

```bash
for run_number in 1 2 3 4 5; do
  STATISTICS_QUERY_BASELINE_RUN_ID="statistics-phase4a-before-bcfeef4-run-$run_number" \
    performance/statistics/run-baseline.sh
done

for run_number in 1 2 3 4 5; do
  STATISTICS_QUERY_BASELINE_RUN_ID="statistics-phase4a-after-5e60a76-run-$run_number" \
    performance/statistics/run-baseline.sh
done
```

원시 JSON은 다음 경로에 생성되며 Git에는 포함하지 않는다.

```text
performance/results/statistics-phase4a-before-bcfeef4-run-*/
performance/results/statistics-phase4a-after-5e60a76-run-*/
```

## 남은 제한

- 전체 기간의 회고 수와 카테고리 분포를 계산하므로 전체 통계의 문서 검사 수는
  그대로다. 회고 수가 더 커지면 MongoDB aggregation과 사전 집계를 별도로
  비교해야 한다.
- 새 인덱스는 저장 공간과 회고 생성 시 index 갱신 비용을 추가한다.
- 과거 문서에 `createdAt`이 없으면 연도 범위 query에서 제외된다. 운영 적용 전
  누락 문서 수를 확인해야 한다.
- 합성 fixture의 query plan과 BSON 크기 비교이며 endpoint latency를 측정하지
  않았다.
- EC2 배포와 운영 collection의 index 생성 시간·실행계획 확인은 범위에서 제외했다.
