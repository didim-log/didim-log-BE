# 문제 추천 카테고리 확장 검색 기능 구현

## 📋 개요

문제 추천 시스템에 **카테고리 확장 검색(Category Expansion Search)** 기능을 추가하여, 상위 개념의 카테고리를 선택하면 하위 태그들도 자동으로 포함하여 검색할 수 있도록 개선했습니다.

## 🎯 문제점

기존에는 사용자가 'Graph Theory'를 선택하면 정확히 태그가 'Graph Theory'인 문제만 검색되었습니다. 하지만 알고리즘 특성상 'Graph Theory'는 상위 개념이고, 'BFS', 'DFS', 'Dijkstra' 등 하위 개념(자식 태그)으로 분류된 문제들도 'Graph Theory' 범주에 포함되어야 합니다.

## ✨ 해결 방안

검색어(카테고리)가 입력되면, 해당 카테고리의 **하위 태그들을 자동으로 포함하여 `OR` 조건(IN 절)으로 검색**하도록 로직을 수정했습니다.

## 🔧 구현 내용

### 1. AlgorithmHierarchyUtils 클래스 생성

알고리즘 계층 구조를 정의하는 유틸리티 클래스를 생성했습니다.

**주요 기능:**
- 주요 알고리즘의 부모-자식 관계를 `Map<String, List<String>>` 형태로 정의
- `getExpandedTags(category)`: 입력받은 카테고리를 확장된 태그 리스트로 변환 (자기 자신 + 하위 태그들)
- `findCategoryEnglishName(category)`: 입력받은 카테고리를 englishName으로 변환

**정의된 계층 구조:**
- **Graph Theory** → BFS, DFS, Dijkstra, Bellman–ford, Floyd–warshall, Topological Sorting, MST, LCA, Bipartite Matching 등 (29개 하위 태그)
- **Dynamic Programming** → Knapsack, LCS, LIS, Bitmask, DP On Trees 등 (10개 하위 태그)
- **Data Structures** → Stack, Queue, Deque, Segment Tree, Trie 등 (22개 하위 태그)
- **Mathematics** → Number Theory, Primality Test, Sieve, Combinatorics 등 (20개 하위 태그)
- **String** → KMP, Trie, Suffix Array, Aho-corasick 등 (12개 하위 태그)

### 2. ProblemRepositoryCustom 인터페이스 및 구현체

MongoDB의 tags 배열 필드에 대해 확장된 태그 리스트로 검색하는 메서드를 추가했습니다.

**추가된 메서드:**
```kotlin
fun findByLevelBetweenAndTagsIn(min: Int, max: Int, expandedTags: List<String>): List<Problem>
```

**동작 방식:**
- 문제의 `tags` 리스트 중 하나라도 확장된 태그 목록에 포함되면 검색됨
- MongoDB의 `$in` 연산자를 사용하여 배열 필드 검색

### 3. RecommendationService 수정

카테고리 확장 검색 로직을 적용했습니다.

**변경 사항:**
- 기존: `findByLevelBetweenAndCategory()` 사용 (단일 카테고리만 검색)
- 변경: `findByLevelBetweenAndTagsIn()` 사용 (확장된 태그 리스트로 검색)
- `AlgorithmHierarchyUtils.getExpandedTags()`를 사용하여 상위 카테고리 선택 시 하위 태그 자동 포함

### 4. 테스트 코드

**AlgorithmHierarchyUtilsTest:**
- Graph Theory, Dynamic Programming, Data Structures, String 확장 검색 테스트
- enum 이름, englishName, 한글 이름 모두 지원 확인
- 계층 구조에 없는 카테고리는 자기 자신만 반환 확인

**RecommendationServiceTest:**
- `findByLevelBetweenAndCategory` 대신 `findByLevelBetweenAndTagsIn` 사용하도록 수정
- 확장된 태그 리스트를 모킹하여 테스트

## 📝 사용 예시

### 예시 1: Graph Theory 선택 시

**요청:**
```http
GET /api/v1/problems/recommend?count=10&category=Graph Theory
```

**검색되는 태그:**
- Graph Theory (자기 자신)
- Breadth-first Search (BFS)
- Depth-first Search (DFS)
- Dijkstra's
- Bellman–ford
- Floyd–warshall
- Topological Sorting
- Minimum Spanning Tree (MST)
- Lowest Common Ancestor (LCA)
- ... (총 29개 하위 태그)

### 예시 2: Dynamic Programming 선택 시

**요청:**
```http
GET /api/v1/problems/recommend?count=10&category=DP
```

**검색되는 태그:**
- Dynamic Programming (자기 자신)
- Knapsack
- Longest Common Subsequence (LCS)
- Longest Increasing Sequence Problem (LIS)
- Bitmask
- ... (총 10개 하위 태그)

### 예시 3: 계층 구조에 없는 카테고리

**요청:**
```http
GET /api/v1/problems/recommend?count=10&category=Implementation
```

**검색되는 태그:**
- Implementation (자기 자신만)

## 🔍 기술적 세부사항

### 태그 매칭 방식

1. **입력 정규화**: 입력받은 카테고리를 `TagUtils.normalizeTagName()`으로 정규화
2. **ProblemCategory 매칭**: enum 이름, englishName, koreanName 모두 매칭 시도
3. **계층 구조 조회**: 매칭된 카테고리의 하위 태그 리스트 조회
4. **englishName 변환**: 하위 태그들을 모두 englishName으로 변환 (DB 저장 형식)
5. **MongoDB 검색**: `tags` 배열 필드에 대해 `$in` 연산자로 검색

### MongoDB 쿼리

```kotlin
Criteria.where("level")
    .gte(min)
    .lte(max)
    .and("tags")
    .`in`(expandedTags)
```

문제의 `tags` 배열 중 하나라도 `expandedTags`에 포함되면 검색됩니다.

## 📊 영향 범위

### 변경된 파일

1. **신규 파일:**
   - `src/main/kotlin/com/didimlog/application/utils/AlgorithmHierarchyUtils.kt`
   - `src/main/kotlin/com/didimlog/domain/repository/ProblemRepositoryCustom.kt`
   - `src/main/kotlin/com/didimlog/domain/repository/ProblemRepositoryImpl.kt`
   - `src/test/kotlin/com/didimlog/application/utils/AlgorithmHierarchyUtilsTest.kt`

2. **수정된 파일:**
   - `src/main/kotlin/com/didimlog/application/recommendation/RecommendationService.kt`
   - `src/main/kotlin/com/didimlog/domain/repository/ProblemRepository.kt`
   - `src/test/kotlin/com/didimlog/application/recommendation/RecommendationServiceTest.kt`
   - `DOCS/API_SPECIFICATION.md`

### 호환성

- **하위 호환성 유지**: 기존 API 스펙과 완전히 호환됩니다.
- **기존 동작 보장**: 계층 구조에 없는 카테고리는 기존과 동일하게 자기 자신만 검색됩니다.
- **성능 영향**: MongoDB의 `$in` 연산자는 인덱스를 활용하므로 성능 저하가 최소화됩니다.

## ✅ 테스트 결과

- **AlgorithmHierarchyUtilsTest**: 8개 테스트 모두 통과
- **RecommendationServiceTest**: 12개 테스트 모두 통과

## 🚀 향후 개선 사항

1. **계층 구조 확장**: 필요에 따라 추가 알고리즘 계층 구조 정의 가능
2. **캐싱**: 확장된 태그 리스트를 캐싱하여 성능 최적화 가능
3. **설정 파일 분리**: 계층 구조를 설정 파일로 분리하여 유지보수성 향상

## 📚 참고사항

- MongoDB의 `tags` 필드는 배열 타입이며, 문제 하나에 여러 태그가 포함될 수 있습니다.
- 확장 검색은 문제의 `tags` 배열 중 하나라도 확장된 태그 목록에 포함되면 검색됩니다.
- 계층 구조는 `AlgorithmHierarchyUtils`의 `HIERARCHY_MAP`에 하드코딩되어 있으며, 필요 시 확장 가능합니다.

