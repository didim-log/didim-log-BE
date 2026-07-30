# 문제 수집과 카테고리 검색

## 목적

문제 수집과 카테고리는 별도 기능이 아니라 하나의 메타데이터 흐름이다. solved.ac에서 받은 태그를 수집 단계에서 정규화해 `problems` 문서에 저장하고, 추천 단계가 같은 대표 카테고리와 태그를 `EXACT`, `HIERARCHY`, `RELATED` 정책으로 조회한다.

상세 본문 수집은 이 메타데이터에 BOJ 문제 설명과 예제를 보강하는 후속 작업이다.

## 실제 요청과 저장 위치

| 요청 | 역할 | 저장 위치 |
| --- | --- | --- |
| `POST /api/v1/admin/problems/collect-metadata?start=&end=` | solved.ac 메타데이터 비동기 수집 | MongoDB `problems`, Redis 작업 상태 |
| `GET /api/v1/admin/problems/collect-metadata/status/{jobId}` | 메타데이터 작업 상태 조회 | Redis |
| `POST /api/v1/admin/problems/collect-details` | 상세 HTML이 없는 문제 비동기 수집 | MongoDB `problems`, Redis 작업 상태 |
| `POST /api/v1/admin/problems/refresh-details` | 상세 강제 재수집 | MongoDB `problems`, Redis 작업 상태 |
| `GET /api/v1/problems/recommend` | 카테고리 정책을 적용한 추천 | `problems`, `students` 조회 |
| `GET /api/v1/problems/categories/meta` | 부모·자식·연관 카테고리 메타 조회 | 코드의 정적 계층 정의 |

관리 작업 생성·취소·재시도 기록은 MongoDB `admin_audit_logs`에도 저장한다.

## 전체 흐름

```mermaid
flowchart TD
    A["관리자: 메타데이터 수집 요청"] --> B["ProblemCollectorController"]
    B --> C["Redis에 PENDING 상태·작업 index 원자 저장"]
    C --> D["taskExecutor worker"]
    D --> E["SolvedAcClient.fetchProblem"]
    E --> F["ProblemCategoryMapper로 태그 정규화"]
    F --> G["MongoDB problems upsert"]
    H["관리자: 상세 수집 요청"] --> H2["ProblemCollectorController"]
    H2 --> H3["Redis에 PENDING 상태·작업 index 원자 저장"]
    H3 --> H4["taskExecutor worker"]
    H4 --> I["BojCrawler.crawlProblemDetails"]
    I --> G
    G --> J["사용자: /problems/recommend"]
    J --> K["입력 별칭·카테고리 정규화"]
    K --> L{"filterMode"}
    L -->|EXACT| M["대표 category 일치"]
    L -->|HIERARCHY| N["대표 category + 하위 tags"]
    L -->|RELATED| O["선택 category + 부모·형제 + 하위 tags"]
    M --> P["티어·언어·미풀이 필터"]
    N --> P
    O --> P
```

## 코드 경로

### 1. 메타데이터 수집과 정규화

1. `ProblemCollectorController.collectMetadata`가 범위와 관리자 정보를 `ProblemCollectorService.collectMetadataAsync`에 전달한다.
2. 서비스는 Redis Lua로 `PENDING` 상태와 sorted index를 함께 만든 뒤 `taskExecutor.execute`로 worker를 등록하고 `jobId`를 즉시 반환한다.
3. `collectMetadataAsyncInternal`은 번호별로 `SolvedAcClient.fetchProblem`을 호출한다.
4. `ProblemCategoryMapper.extractTagsToEnglish`는 한국어 표시명을 `ProblemCategory`로 변환하고 영문 정식명 목록으로 중복 제거한다.
5. `determineCategory`는 첫 번째 정규 태그를 대표 `category`로 사용한다. 태그가 없으면 `IMPLEMENTATION`이다.
6. 신규·기존 문제 모두 메타데이터 소유 필드만 MongoDB modifier upsert로 갱신하며, 기존 상세·URL·언어는 보존한다.
7. 처리 수, 성공·실패 수, 체크포인트는 항목마다 Redis 작업 상태에 반영한다.

상태 변경은 읽은 Redis JSON 원문을 기대값으로 비교하는 Lua CAS로 처리한다. 오래된
worker의 갱신은 반영하지 않으며 `COMPLETED`, `FAILED`, `CANCELLED` 상태는 다시
변경하지 않는다. sorted index는 작업을 만들 때 한 번만 기록한다.

### 2. 상세 보강

1. `collectDetailsBatchAsync`는 `descriptionHtml`이 없는 문제를 먼저 조회한다.
2. worker의 `collectDetailsBatchAsyncInternal`이 `BojCrawler.crawlProblemDetails`를 호출한다.
3. `#problem_description`, `#problem_input`, `#problem_output`과 최대 5개의 입출력 예제를 읽어 같은 `problems` 문서에 저장한다.

### 3. 카테고리 추천 검색

1. `ProblemController.recommendProblems`가 `category`, `language`, `filterMode`를 `RecommendationService.recommendProblemsDetailed`에 넘긴다.
2. `AlgorithmHierarchyUtils.findCategoryEnglishName`은 `TagUtils`의 제한된 별칭과 enum 이름·영문명·한글명을 영문 정식명으로 맞춘다.
3. `EXACT`는 대표 카테고리만, `HIERARCHY`는 선택 카테고리의 직접 하위 태그까지, `RELATED`는 부모와 형제 카테고리 및 각 확장 태그까지 대상 목록을 만든다.
4. `findRecommendationCandidates`는 난이도 범위와 `category IN (...) OR tags IN (...)` 조건을 묶어 `problems`를 한 번 조회한다.
5. `level`이 없는 이전 문서는 조회 결과에서만 `difficultyLevel`을 `level`로 사용하며, 두 필드가 함께 있으면 현재 `level`을 우선한다.
6. 난이도는 `max(1, tierLevel - 2)..tierLevel + 2` 범위로 제한하고, tier level이 0 이하면 1~2를 사용한다. 이후 언어와 풀이 기록이 있는 문제를 제외하고 무작위로 요청 개수만 반환한다.
7. `ProblemCategoryViewResolver`는 응답 표시용 `primaryCategory`, `secondaryCategories`, `normalizedTags`를 다시 계산한다.

주요 구현 파일:

- `ui/controller/ProblemCollectorController.kt`
- `application/problem/collector/ProblemCollectorService.kt`
- `infra/solvedac/ProblemCategoryMapper.kt`
- `application/recommendation/RecommendationService.kt`
- `application/utils/AlgorithmHierarchyUtils.kt`
- `domain/repository/ProblemRepositoryImpl.kt`

## 데모 fixture 경계

`portfolio-fixture`이면서 `prod`가 아닐 때 `PortfolioSolvedAcClient`와 `PortfolioBojCrawler`가 외부 호출을 대체한다.

- 1000~1005번은 고정 제목·레벨·태그·상세 본문을 반환한다.
- 그 밖의 번호도 기본 fixture 응답을 반환한다.
- 비동기 executor, Redis 작업 상태, 카테고리 변환, MongoDB 저장과 추천 쿼리는 실제 경로를 사용한다.

운영에서는 `SolvedAcWebClient`와 `BojCrawler`가 실제 외부 응답을 사용한다.

## 알려진 제약

- 항목 하나의 수집 실패는 `failCount`로 기록되지만 전체 작업은 `COMPLETED`가 될 수 있다.
- 취소는 항목 사이에서 상태를 확인하는 방식이라 진행 중 HTTP 호출이나 대기 시간을 즉시 중단하지 않는다.
- 작업 상태는 24시간 TTL이며 서버 재시작 뒤 진행 중 작업을 복구하는 worker는 없다.
- 상태 전이는 같은 Redis를 공유하는 여러 인스턴스에서 CAS로 조정한다. 구·신 worker의 혼합 실행은 지원하지 않으므로 전체 교체가 필요하다.
- 작업 생성 시 상태 키와 sorted index를 함께 다루는 Lua는 standalone Redis 구성을 기준으로 한다.
- 작업 목록은 sorted index에서 ID를 조회한 뒤 상태를 ID별로 읽는 1+N 구조가 남아 있다.
- 상세 대상은 worker 실행 전에 목록으로 고정되며, 예제는 최대 5쌍만 수집한다.
- 한국어 표시명이 없는 solved.ac 태그 key도 `fromKorean`에 전달되므로 일부 태그가 `Unknown`으로 합쳐질 수 있다.
- 계층은 코드에 하드코딩된 직접 부모·자식 관계다. `RELATED`는 유사도 검색이 아니라 부모·형제 확장이다.
- `EXACT`는 보조 태그만 일치하는 문제를 찾지 않는다.
- 추천 후보 조회는 한 번이지만 언어와 풀이 기록 필터 전에 후보를 모두 읽으며, `problems` 인덱스는 자동 생성되지 않는다.
- `difficultyLevel`만 있는 이전 문제는 조회 결과에서만 보정하며 저장 문서는 바꾸지 않는다.
- 알 수 없는 카테고리 입력은 오류로 거절하지 않고 빈 결과가 될 수 있다.
- 응답 표시용 대표 카테고리는 별도 우선순위로 계산하므로 저장된 대표 `category`와 다를 수 있다.
