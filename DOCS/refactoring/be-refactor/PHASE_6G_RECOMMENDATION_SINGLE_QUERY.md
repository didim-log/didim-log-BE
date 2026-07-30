# Phase 6G — 추천 카테고리 단일 조회

## 문제

카테고리 추천은 확장된 카테고리마다 `problems`를 따로 조회한 뒤 마지막에 태그
조회 결과를 합쳤다. `BFS`를 기본 `RELATED` 모드로 요청하면 대표 카테고리
30개가 만들어져 다음 순서로 실행됐다.

```text
students find 1회
→ 대표 카테고리별 problems find 30회
→ 확장 태그 problems find 1회
→ 중복 제거·언어 필터·풀이 기록 제외
→ shuffle / take
```

난이도 조건도 조회 경로마다 달랐다.

- 대표 카테고리 조회는 `level`만 사용하고 최솟값·최댓값을 제외했다.
- 태그 조회는 `level` 또는 `difficultyLevel`을 사용하고 경계를 포함했다.
- `difficultyLevel`만 있는 문서는 태그 조회에 잡힌 뒤 필수 `level` 매핑에서
  예외가 발생했다.

## 변경

### 카테고리와 태그 조건 통합

`RecommendationService`가 모드에 맞는 대표 카테고리와 확장 태그를 먼저 만든 뒤
`findRecommendationCandidates`를 한 번 호출한다.

```text
유효 난이도
  level이 있으면 level
  level이 없으면 difficultyLevel

추천 후보
  유효 난이도 BETWEEN min AND max
  AND
  (
    category IN targetCategories
    OR tags IN expandedTags
  )
```

`EXACT`는 `expandedTags`를 빈 목록으로 넘겨 `category` 조건만 사용한다.
`HIERARCHY`와 `RELATED`의 확장 범위 계산은 바꾸지 않았다. MongoDB의 한 문서가
대표 카테고리와 태그 조건에 모두 맞아도 결과에는 한 번만 포함된다.

### 레거시 난이도 읽기

현재 `level`이 범위에 들거나, `level`이 없으면서 `difficultyLevel`이 범위에 드는
문서만 조회한다. 두 필드가 함께 있으면 현재 `level`을 우선한다.

조회 결과는 raw `Document`로 받은 뒤 `level`이 없는 결과에만
`difficultyLevel`을 복사하고 기존 `MappingMongoConverter`로 `Problem`을 만든다.
이는 조회 결과에만 적용되며 저장된 문서를 갱신하지 않는다.

### 유지한 후처리

- `EXACT`, `HIERARCHY`, `RELATED`의 카테고리 확장 범위
- `matchedByPrimary`, `matchedByTags`, `expandedFrom`
- 제목과 본문을 함께 보는 언어 필터
- 풀이 기록이 있는 문제 제외
- `shuffled().take(count)`
- API 응답 필드

현재 풀이 기록 제외 기준은 성공 여부와 관계없이 같은 `problemId`가 한 번이라도
기록됐는지 여부다. 이 정책은 이번 조회 변경에서 그대로 유지했다.

## 비교 조건

- Before 코드 SHA: `239a7267a0b937944eba1784fe771d101dd2e846`
- After 코드 SHA: `7617b017442f5aea768d9bc3f16034ecdab47e89`
- 계측 시 두 worktree 모두 clean
- 외부 계측 harness SHA-256:
  `36b6a76611c062ac30315c51b18447051b1fc535791988037b40d8f9ce7fa531`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- 학생 tier level: 3
- 요청: `category=BFS`, `filterMode=RELATED`, `count=50`
- 현재 난이도 문제 6건
  - 대표 카테고리 일치
  - 부모 카테고리 일치
  - 형제 태그 일치
  - 대표 카테고리와 태그 동시 일치
  - 무관한 문제
  - 범위 밖 문제

같은 harness를 Before와 After worktree에 연결해 public
`RecommendationService.recommendProblemsDetailed`를 호출했다.
`CommandListener`는 fixture 저장을 마친 뒤 활성화했으며 `find`, `aggregate`,
`getMore`를 컬렉션별로 기록했다.

무작위 반환 순서의 영향을 없애기 위해 결과를 문제 ID로 정렬하고
`id`, `matchedByPrimary`, `matchedByTags`, 정렬한 `expandedFrom`을 묶어
SHA-256을 계산했다.

## 측정 결과

| 직접 비교 항목 | Before | After | 변화 |
| --- | ---: | ---: | ---: |
| `problems` find | 31 | 1 | 96.77% 감소 |
| `students`를 포함한 find | 32 | 2 | 93.75% 감소 |
| `problems` getMore | 0 | 0 | 동일 |
| 추천 결과 | 4건 | 4건 | 동일 |

기능 결과 SHA-256은 전후 모두
`26cdd78dbcbe7da25cf14c8ff81d741620da9ff9e700ee565ce174273086e069`다.

`problems` 31회는 BFS가 속한 Graph Theory의 부모·형제 확장으로 대표 카테고리
조회 30회와 태그 조회 1회가 실행된 값이다. 96.77%와 93.75%는 이 고정 fixture의
MongoDB `find` 명령 수 감소율이며 응답 시간이나 운영 처리량 개선율이 아니다.

## 정합성 검증

변경 전 실제 MongoDB에서 다음을 재현했다.

| 항목 | 변경 전 결과 |
| --- | --- |
| 난이도 최솟값 1·최댓값 5의 대표 카테고리 | 배타 조건으로 누락 |
| `difficultyLevel`만 있는 대표 카테고리 | `level` 조건에서 누락 |
| `difficultyLevel`만 있는 태그 문제 | `Problem.level` 매핑 예외 |

변경 후 통합 테스트는 다음을 확인한다.

- 난이도 1과 5 포함, 6 제외
- 대표 카테고리만 일치한 문제와 태그만 일치한 문제 포함
- 두 조건에 함께 맞는 문제를 한 번만 반환
- EXACT에서 태그 전용 문제 제외
- HIERARCHY에서 선택 카테고리의 확장 태그 포함
- RELATED에서 부모·형제 카테고리와 태그 포함
- `difficultyLevel`만 있는 대표·태그 문제를 `Problem.level`로 변환
- 두 난이도 필드가 함께 있으면 현재 `level` 우선
- SUCCESS와 FAIL 풀이 기록 모두 기존 정책대로 제외
- 서비스 호출당 `students find 1`, `problems find 1`

## 전체 검증과 커버리지

전용 MongoDB와 Redis에 연결해 `clean check`를 실행했다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 731개 통과 |
| 통합 테스트 | 208개 중 199개 통과, 조건부 9개 제외 |
| core-v1 Line / Branch / Class | 88.75% / 66.38% / 94.83% |
| full-v1 Line / Branch / Class | 77.20% / 58.03% / 82.59% |
| `ProblemRepositoryImpl` Line / Branch / Method | 100% / 100% / 100% |

변경 전 merged report에서 `ProblemRepositoryImpl`은 실행 대상 42줄 중 31줄,
6개 메서드 중 4개가 실행됐다. 변경 후에는 실행 대상 51줄과 8개 분기가 모두
실행됐다. 추천 persistence 경로의 실제 MongoDB 통합 테스트는 0개에서 3개로
늘었다. 리팩터링으로 실행 대상 줄과 메서드 수도 바뀌었으므로 이 수치를 응답
성능 향상률로 해석하지 않는다.

## 재현

정합성과 명령 수:

```bash
SPRING_DATA_MONGODB_URI=mongodb://127.0.0.1:27218/didimlog-test \
TEST_MONGO_PORT=27218 \
./gradlew integrationTest \
  --tests 'com.didimlog.application.recommendation.RecommendationQueryIntegrationTest'
```

서비스 단위 테스트:

```bash
./gradlew test \
  --tests 'com.didimlog.application.recommendation.RecommendationServiceTest'
```

## 남은 제한

- `spring.data.mongodb.auto-index-creation`이 꺼져 있고 현재 초기화 코드가
  `problems` 인덱스를 만들지 않는다. 조회 횟수는 한 번으로 줄었지만 데이터가
  많을 때의 `totalDocsExamined`는 별도 `explain`으로 확인해야 한다.
- 추천 후보를 모두 읽은 뒤 언어와 풀이 기록을 제외하므로 후보 수에 비례한
  애플리케이션 메모리 사용은 남아 있다.
- 레거시 난이도는 읽을 때만 보정한다. 저장 문서의 backfill은 수행하지 않았다.
- 카테고리와 태그는 저장된 영문 표준명과 정확히 일치하는 값만 찾는다.
- 응답 시간, 처리량, JVM heap과 운영 데이터 분포는 측정하지 않았다.
- EC2 배포와 운영 MongoDB 실행 계획 확인은 범위에서 제외했다.
