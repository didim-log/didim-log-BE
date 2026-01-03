# PR Summary: 태그 별칭 매핑 기능 추가

## 📋 개요

사용자가 축약형 태그(예: "BFS", "DFS", "DP")로 문제를 필터링할 때 빈 결과가 반환되는 문제를 해결하기 위해 태그 별칭 매핑 기능을 추가했습니다.

## 🎯 변경 사항

### 1. TagUtils 유틸리티 클래스 추가
- **파일**: `src/main/kotlin/com/didimlog/application/utils/TagUtils.kt`
- **기능**: 축약형 태그를 공식 전체 이름으로 자동 변환
- **지원 별칭**:
  - BFS → Breadth-first Search
  - DFS → Depth-first Search
  - DP → Dynamic Programming
  - MST → Minimum Spanning Tree
  - LCA → Lowest Common Ancestor
  - KMP → Knuth–morris–pratt
  - FFT → Fast Fourier Transform
  - LIS → Longest Increasing Sequence Problem
  - LCS → Longest Common Subsequence
  - CRT → Chinese Remainder Theorem
  - 기타 일반적인 별칭들

### 2. RecommendationService 개선
- **파일**: `src/main/kotlin/com/didimlog/application/recommendation/RecommendationService.kt`
- **변경 사항**:
  - `findCandidateProblems` 메서드에서 `TagUtils.normalizeTagName` 사용
  - ProblemCategory enum 이름 및 englishName 모두 매칭 지원
  - 축약형 태그 입력 시 자동으로 공식 전체 이름으로 변환

### 3. API 명세서 업데이트
- **파일**: `DOCS/API_SPECIFICATION.md`
- **변경 사항**:
  - `/api/v1/problems/recommend` API에 태그 별칭 지원 내용 추가
  - 지원 형식(축약형/공식 전체 이름/Enum 이름) 명시
  - 예시 요청 추가 (축약형 태그, 공식 전체 이름)
  - `language` 파라미터 설명 추가

### 4. Swagger 문서 개선
- **파일**: `src/main/kotlin/com/didimlog/ui/controller/ProblemController.kt`
- **변경 사항**:
  - `category` 파라미터 설명에 태그 별칭 자동 변환 기능 명시
  - 축약형 태그 지원 안내 추가

## 🔍 기술적 세부사항

### 클린코드 원칙 준수
- ✅ Indent Depth 1 이하 유지
- ✅ `else` 예약어 사용 금지 (Early Return 패턴)
- ✅ 한 메서드는 한 가지 일만 수행
- ✅ 불필요한 import 제거

### 구현 방식
1. **태그 정규화**: `TagUtils.normalizeTagName` 메서드로 입력된 태그를 정규화
2. **별칭 매칭**: `TAG_ALIASES` Map에서 대소문자 무시 매칭
3. **Enum 매칭**: ProblemCategory enum의 `name`과 `englishName` 모두 확인
4. **폴백 처리**: 매칭 실패 시 정규화된 원본 문자열 사용

## ✅ 테스트

- 모든 기존 테스트 통과 확인
- TagUtils 및 RecommendationService 테스트 통과

## 📝 API 변경사항

### GET `/api/v1/problems/recommend`

**변경 전:**
- `category` 파라미터: Enum 이름만 지원 (예: "IMPLEMENTATION", "GRAPH")

**변경 후:**
- `category` 파라미터: 다음 형식 모두 지원
  - 축약형 태그: "BFS", "DFS", "DP" 등
  - 공식 전체 이름: "Breadth-first Search", "Depth-first Search" 등
  - Enum 이름: "IMPLEMENTATION", "GRAPH" 등

**예시:**
```http
# 축약형 태그 사용 (자동 변환됨)
GET /api/v1/problems/recommend?count=10&category=BFS

# 공식 전체 이름 사용
GET /api/v1/problems/recommend?count=10&category=Breadth-first Search

# Enum 이름 사용
GET /api/v1/problems/recommend?count=10&category=IMPLEMENTATION
```

## 🎨 사용자 경험 개선

- **이전**: 사용자가 "BFS"로 검색하면 빈 결과 반환
- **이후**: 사용자가 "BFS"로 검색하면 자동으로 "Breadth-first Search"로 변환되어 정확한 결과 반환

## 🔗 관련 이슈

- 사용자가 축약형 태그로 필터링할 때 빈 결과가 나오는 문제 해결
- DB에 저장된 공식 전체 이름과 사용자 입력 간 매핑 자동화

## 📚 참고 문서

- `DOCS/API_SPECIFICATION.md`: API 명세서
- `DOCS/PR_GUIDE.md`: 코드 스타일 가이드
- `DOCS/COMMIT_CONVENTION.md`: 커밋 컨벤션
