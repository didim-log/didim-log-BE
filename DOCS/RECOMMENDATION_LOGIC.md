# 문제 추천 시스템 로직 명세서

## 개요

DidimLog의 문제 추천 시스템은 학생의 현재 티어를 기반으로 적절한 난이도의 문제를 추천합니다.
카테고리 필터링을 지원하여 특정 알고리즘 분야에 집중할 수 있습니다.

## 추천 로직

### 1. 기본 추천 로직

- **타겟 난이도 범위**: 학생의 현재 티어 레벨 범위에서 -2 ~ +2 단계
  - **UNRATED 티어(레벨 0) 특별 처리**: Bronze V(레벨 1) ~ Bronze IV(레벨 2) 문제 추천
  - 예: BRONZE 티어(레벨 1~5) 학생 → 레벨 (1-2) ~ (5+2) = 레벨 1~7 문제 추천 (단, 최소 레벨은 1)
  - 예: SILVER 티어(레벨 6~10) 학생 → 레벨 (6-2) ~ (10+2) = 레벨 4~12 문제 추천
  - 예: GOLD 티어(레벨 11~15) 학생 → 레벨 (11-2) ~ (15+2) = 레벨 9~17 문제 추천
  - 예: RUBY 티어(레벨 26~30) 학생 → 레벨 (26-2) ~ (30+2) = 레벨 24~32 문제 추천

- **최소 레벨 제약**: 계산된 최소 레벨이 1 미만인 경우 1로 제한
  - 예: BRONZE 티어(레벨 1~5) 학생의 경우, (1-2) = -1이지만 최소 레벨 1로 제한

- **무한 성장 로직**: 최대 티어(RUBY)에 도달한 경우에도 상위 난이도 문제를 추천
  - RUBY(레벨 26~30) 학생 → 레벨 24~32 문제 추천 (레벨 32는 Solved.ac 최대 레벨 30을 초과하지만, 무한 성장을 위해 허용)

### 2. 카테고리 필터링 (선택사항)

- **카테고리 지정 시**: 해당 카테고리와 타겟 레벨 범위에 맞는 문제만 추천
- **카테고리 미지정 시**: 타겟 레벨 범위의 모든 문제 중에서 추천

### 3. 필터링 조건

1. **난이도 필터**: 타겟 레벨 범위에 해당하는 문제
   - **UNRATED**: 레벨 1~2 (Bronze V ~ Bronze IV)
   - **기타 티어**: 현재 티어 레벨 범위에서 -2 ~ +2 단계
2. **카테고리 필터**: 카테고리가 지정된 경우 해당 카테고리만 (선택사항)
3. **미해결 필터**: 학생이 아직 풀지 않은 문제만 추천
4. **랜덤 선택**: 필터링된 문제 중 랜덤으로 선택

## API 사용 예시

### 기본 추천 (카테고리 없음)
```
GET /api/v1/problems/recommend?count=5
```

### 카테고리별 추천
```
GET /api/v1/problems/recommend?count=5&category=IMPLEMENTATION
GET /api/v1/problems/recommend?count=5&category=GRAPH
GET /api/v1/problems/recommend?count=5&category=DP
```

## 구현 세부사항

### Repository 레이어
- `findByLevelBetween(min: Int, max: Int)`: 레벨 범위로 조회
- `findByLevelBetweenAndCategory(min: Int, max: Int, category: String)`: 레벨 범위 + 카테고리로 조회

### Service 레이어
- `recommendProblems(bojId: String, count: Int, category: String?)`: 추천 로직 실행
  - `category`가 null이면 레벨 범위만 체크
  - `category`가 있으면 레벨 범위 + 카테고리 체크

## 주의사항

- 카테고리는 대소문자를 구분합니다. (예: "IMPLEMENTATION", "GRAPH", "DP")
- 존재하지 않는 카테고리를 지정하면 빈 리스트가 반환됩니다.
- 타겟 레벨 범위에 해당하는 문제가 없으면 빈 리스트가 반환됩니다.
