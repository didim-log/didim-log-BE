# 도메인 변경 이력

이 문서는 DidimLog 프로젝트의 도메인 모델 변경사항을 기록합니다.

## Phase 5: Service Enhancement (v1.2) - Domain & DB Schema Update

### 변경 일자
2024년 12월

### 변경 사항

#### 1. Quote 엔티티 추가 (신규)
- **목적**: 동기부여 명언을 저장하여 사용자에게 랜덤으로 제공
- **필드**:
  - `id`: String? (MongoDB ObjectId)
  - `content`: String (명언 내용, 필수)
  - `author`: String (저자명, 기본값: "Unknown")
- **컬렉션**: `quotes`

#### 2. Student 엔티티 수정
- **목적**: 게이미피케이션 요소 추가 (연속 풀이 일수 추적)
- **추가 필드**:
  - `consecutiveSolveDays`: Int (기본값: 0, 연속 풀이 일수)
  - `lastSolvedAt`: LocalDate? (마지막으로 문제를 푼 날짜, 시간 제외)
- **도메인 로직 추가**:
  - `solveProblem()` 메서드에서 연속 풀이 일수 자동 계산
  - `calculateConsecutiveSolveDays()`: 마지막 풀이 날짜를 기준으로 연속 일수 계산
    - 어제 풀었으면 증가, 오늘 이미 풀었으면 유지, 그 이전이면 1로 초기화

#### 3. Retrospective 엔티티 수정
- **목적**: 회고 기능 고도화 (즐겨찾기, 카테고리 분류)
- **추가 필드**:
  - `isBookmarked`: Boolean (기본값: false, 즐겨찾기 여부)
  - `mainCategory`: ProblemCategory? (주요 풀이 알고리즘, nullable)
- **도메인 로직 추가**:
  - `toggleBookmark()`: 즐겨찾기 상태를 토글하는 메서드

### 추천 로직 영향 분석

이번 도메인 변경사항은 현재 추천 로직에 직접적인 영향을 주지 않습니다.

- **북마크(`isBookmarked`)**: 회고 조회 및 필터링에만 사용되며, 문제 추천 로직과는 무관
- **주요 카테고리(`mainCategory`)**: 회고 분류 및 통계에 사용되며, 문제 추천은 여전히 문제의 카테고리(`Problem.category`)를 기반으로 수행
- **연속 풀이 일수(`consecutiveSolveDays`)**: 게이미피케이션 목적으로만 사용되며, 추천 알고리즘과는 분리

향후 추천 로직 개선 시 고려사항:
- 사용자의 주요 카테고리(`mainCategory`)를 분석하여 해당 카테고리 문제에 가중치를 부여할 수 있음
- 연속 풀이 일수가 높은 사용자에게는 더 도전적인 문제를 추천할 수 있음

### 마이그레이션 가이드

#### 기존 데이터 호환성
- **Student**: 기존 데이터는 `consecutiveSolveDays = 0`, `lastSolvedAt = null`로 자동 설정됨
- **Retrospective**: 기존 데이터는 `isBookmarked = false`, `mainCategory = null`로 자동 설정됨
- MongoDB의 기본값 설정으로 인해 기존 데이터와 호환됨

#### 데이터 마이그레이션 (선택사항)
기존 회고 데이터에 `mainCategory`를 채우고 싶은 경우:
1. 회고 작성 시 문제의 카테고리를 자동으로 `mainCategory`에 저장하도록 로직 추가
2. 기존 회고 데이터는 배치 작업으로 문제 정보를 조회하여 `mainCategory` 업데이트

