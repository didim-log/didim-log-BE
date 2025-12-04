# DidimLog API 명세서

이 문서는 DidimLog 프로젝트의 모든 REST API 엔드포인트를 정리한 명세서입니다.

## 목차

- [AuthController](#authcontroller)
- [ProblemController](#problemcontroller)
- [StudyController](#studycontroller)
- [RetrospectiveController](#retrospectivecontroller)
- [DashboardController](#dashboardcontroller)
- [StudentController](#studentcontroller)
- [QuoteController](#quotecontroller)
- [StatisticsController](#statisticscontroller)
- [LeaderboardController](#leaderboardcontroller)

---

## AuthController

인증 관련 API를 제공합니다. Solved.ac 연동 기반의 회원가입 및 JWT 토큰 기반 로그인을 지원합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/auth/signup` | BOJ ID와 비밀번호를 입력받아 Solved.ac API로 검증 후 회원가입을 진행하고 JWT 토큰을 발급합니다. 비밀번호는 BCrypt로 암호화되어 저장됩니다. Solved.ac의 Rating(점수)을 기반으로 티어를 자동 계산합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - **비밀번호 정책:**<br>    - 영문, 숫자, 특수문자 중 **3종류 이상 조합**: 최소 **8자리** 이상<br>    - 영문, 숫자, 특수문자 중 **2종류 이상 조합**: 최소 **10자리** 이상<br>    - 공백 포함 불가 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/login` | BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생합니다. 로그인 시 Solved.ac API를 통해 Rating 및 Tier 정보를 동기화합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상) | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token<br>- `message` (String): 응답 메시지 ("로그인에 성공했습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |

**예시 요청 (회원가입):**
```http
POST /api/v1/auth/signup
Content-Type: application/json

{
  "bojId": "user123",
  "password": "securePassword123"
}
```

**예시 응답 (회원가입):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiaWF0IjoxNjE2MjM5MDIyLCJleHAiOjE2MTYzMjU0MjJ9.signature",
  "message": "회원가입이 완료되었습니다.",
  "rating": 1223,
  "tier": "GOLD",
  "tierLevel": 13
}
```

**예시 요청 (로그인):**
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "bojId": "user123",
  "password": "securePassword123"
}
```

**예시 응답 (로그인):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiaWF0IjoxNjE2MjM5MDIyLCJleHAiOjE2MTYzMjU0MjJ9.signature",
  "message": "로그인에 성공했습니다.",
  "rating": 1223,
  "tier": "GOLD",
  "tierLevel": 13
}
```

**에러 응답 예시 (유효하지 않은 BOJ ID):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "COMMON_RESOURCE_NOT_FOUND",
  "message": "유효하지 않은 BOJ ID입니다. bojId=invalid"
}
```

**에러 응답 예시 (가입되지 않은 사용자 로그인):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "STUDENT_NOT_FOUND",
  "message": "가입되지 않은 BOJ ID입니다. 회원가입을 진행해주세요. bojId=notfound"
}
```

**에러 응답 예시 (비밀번호 불일치):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "비밀번호가 일치하지 않습니다."
}
```

**에러 응답 예시 (이미 가입된 사용자):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "이미 가입된 BOJ ID입니다. bojId=user123"
}
```

**에러 응답 예시 (비밀번호 정책 위반 - 3종류 조합 시 8자 미만):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PASSWORD",
  "message": "영문, 숫자, 특수문자 3종류 이상 조합 시 최소 8자리 이상이어야 합니다."
}
```

**에러 응답 예시 (비밀번호 정책 위반 - 2종류 조합 시 10자 미만):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PASSWORD",
  "message": "영문, 숫자, 특수문자 중 2종류 이상 조합 시 최소 10자리 이상이어야 합니다."
}
```

**에러 응답 예시 (비밀번호 정책 위반 - 1종류만 사용):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PASSWORD",
  "message": "영문, 숫자, 특수문자 중 최소 2종류 이상을 조합해야 합니다."
}
```

**에러 응답 예시 (비밀번호 정책 위반 - 공백 포함):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PASSWORD",
  "message": "비밀번호에 공백을 포함할 수 없습니다."
}
```

---

## ProblemController

문제 추천 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/problems/recommend` | 학생의 현재 티어보다 한 단계 높은 난이도(UserLevel + 1 ~ +2)의 문제 중, 아직 풀지 않은 문제를 추천합니다. 카테고리를 지정하면 해당 카테고리 문제만 추천합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `count` (Int, optional, default: 1): 추천할 문제 개수<br>  - 유효성: `@Positive` (1 이상)<br>- `category` (String, optional): 문제 카테고리 필터<br>  - 예: "IMPLEMENTATION", "GRAPH", "DP" 등<br>  - 미지정 시 모든 카테고리에서 추천 | `List<ProblemResponse>`<br><br>**ProblemResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 문제 카테고리<br>- `difficulty` (String): 난이도 티어명 (예: "BRONZE", "SILVER")<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL | JWT Token |

**예시 요청 (기본 추천):**
```http
GET /api/v1/problems/recommend?count=3
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (카테고리별 추천):**
```http
GET /api/v1/problems/recommend?count=5&category=IMPLEMENTATION
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답:**
```json
[
  {
    "id": "1000",
    "title": "A+B",
    "category": "IMPLEMENTATION",
    "difficulty": "BRONZE",
    "difficultyLevel": 3,
    "url": "https://www.acmicpc.net/problem/1000"
  },
  {
    "id": "1001",
    "title": "A-B",
    "category": "IMPLEMENTATION",
    "difficulty": "BRONZE",
    "difficultyLevel": 3,
    "url": "https://www.acmicpc.net/problem/1001"
  }
]
```

---

## StudyController

학습 및 문제 풀이 제출 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/study/submit` | 학생이 문제를 풀고 결과를 제출합니다. 풀이 결과가 Solutions에 저장됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`SolutionSubmitRequest`<br>- `problemId` (String, required): 문제 ID<br>  - 유효성: `@NotBlank`<br>- `timeTaken` (Long, required): 풀이 소요 시간 (초 단위)<br>  - 유효성: `@NotNull`, `@Positive` (0보다 커야 함)<br>- `isSuccess` (Boolean, required): 풀이 성공 여부<br>  - 유효성: `@NotNull` | `SolutionSubmitResponse`<br><br>**SolutionSubmitResponse 구조:**<br>- `message` (String): 응답 메시지 ("문제 풀이 결과가 저장되었습니다.")<br>- `currentTier` (String): 현재 티어명 (예: "BRONZE", "SILVER")<br>- `currentTierLevel` (Int): 현재 티어의 Solved.ac 레벨 값 | JWT Token |

**예시 요청:**
```http
POST /api/v1/study/submit
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "problemId": "1000",
  "timeTaken": 120,
  "isSuccess": true
}
```

**예시 응답:**
```json
{
  "message": "문제 풀이 결과가 저장되었습니다.",
  "currentTier": "BRONZE",
  "currentTierLevel": 3
}
```

---

## RetrospectiveController

회고 작성 및 조회 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/retrospectives` | 학생이 문제 풀이 후 회고를 작성합니다. 이미 해당 문제에 대한 회고가 있으면 수정됩니다. | **Query Parameters:**<br>- `studentId` (String, required): 학생 ID<br>- `problemId` (String, required): 문제 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>- `id` (String): 회고 ID<br>- `studentId` (String): 학생 ID<br>- `problemId` (String): 문제 ID<br>- `content` (String): 회고 내용<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식)<br>- `isBookmarked` (Boolean): 북마크 여부<br>- `mainCategory` (String, nullable): 주요 알고리즘 카테고리 | None |
| GET | `/api/v1/retrospectives` | 검색 조건에 따라 회고 목록을 조회합니다. 키워드, 카테고리, 북마크 여부로 필터링할 수 있으며, 페이징을 지원합니다. | **Query Parameters:**<br>- `keyword` (String, optional): 검색 키워드 (내용 또는 문제 ID)<br>- `category` (String, optional): 카테고리 필터 (예: "DFS", "DP")<br>- `isBookmarked` (Boolean, optional): 북마크 여부 (true인 경우만 필터링)<br>- `studentId` (String, optional): 학생 ID 필터<br>- `page` (Int, optional, default: 0): 페이지 번호 (0부터 시작)<br>- `size` (Int, optional, default: 10): 페이지 크기<br>- `sort` (String, optional): 정렬 기준 (예: "createdAt,desc" 또는 "createdAt,asc")<br>  - 기본값: "createdAt,desc" | `RetrospectivePageResponse`<br><br>**RetrospectivePageResponse 구조:**<br>- `content` (List<RetrospectiveResponse>): 회고 목록<br>- `totalElements` (Long): 전체 회고 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | None |
| GET | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 조회합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>(위와 동일) | None |
| POST | `/api/v1/retrospectives/{retrospectiveId}/bookmark` | 회고의 북마크 상태를 토글합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `BookmarkToggleResponse`<br><br>**BookmarkToggleResponse 구조:**<br>- `isBookmarked` (Boolean): 변경된 북마크 상태 | None |
| GET | `/api/v1/retrospectives/template` | 문제 정보를 바탕으로 회고 작성용 마크다운 템플릿을 생성합니다. | **Query Parameters:**<br>- `problemId` (String, required): 문제 ID | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>- `template` (String): 마크다운 형식의 템플릿 문자열 | None |

**예시 요청 (회고 작성):**
```http
POST /api/v1/retrospectives?studentId=student-123&problemId=1000
Content-Type: application/json

{
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다."
}
```

**예시 응답 (회고 작성):**
```json
{
  "id": "retrospective-123",
  "studentId": "student-123",
  "problemId": "1000",
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다.",
  "createdAt": "2024-01-15T10:30:00"
}
```

**예시 요청 (회고 목록 조회 - 기본):**
```http
GET /api/v1/retrospectives?page=0&size=10
```

**예시 요청 (회고 목록 조회 - 키워드 검색):**
```http
GET /api/v1/retrospectives?keyword=DFS&page=0&size=10
```

**예시 요청 (회고 목록 조회 - 카테고리 필터):**
```http
GET /api/v1/retrospectives?category=DFS&page=0&size=10
```

**예시 요청 (회고 목록 조회 - 북마크 필터):**
```http
GET /api/v1/retrospectives?isBookmarked=true&page=0&size=10
```

**예시 요청 (회고 목록 조회 - 정렬):**
```http
GET /api/v1/retrospectives?sort=createdAt,asc&page=0&size=10
```

**예시 응답 (회고 목록 조회):**
```json
{
  "content": [
    {
      "id": "retrospective-123",
      "studentId": "student-123",
      "problemId": "1000",
      "content": "이 문제는 DFS를 사용해서 풀었습니다.",
      "createdAt": "2024-01-15T10:30:00",
      "isBookmarked": true,
      "mainCategory": "DFS"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 10,
  "hasNext": false,
  "hasPrevious": false
}
```

**예시 요청 (북마크 토글):**
```http
POST /api/v1/retrospectives/retrospective-123/bookmark
```

**예시 응답 (북마크 토글):**
```json
{
  "isBookmarked": true
}
```

**예시 요청 (템플릿 생성):**
```http
GET /api/v1/retrospectives/template?problemId=1000
```

**예시 응답 (템플릿 생성):**
```json
{
  "template": "# 문제 제목\n\n## 문제 링크\nhttps://www.acmicpc.net/problem/1000\n\n## 접근 방법\n\n## 코드\n\n```kotlin\n\n```\n\n## 회고\n"
}
```

---

## DashboardController

대시보드 정보 조회 API를 제공합니다. 오늘의 활동 중심으로 경량화된 정보를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/dashboard` | 학생의 오늘의 활동(오늘 푼 문제), 기본 프로필 정보, 랜덤 명언을 포함한 대시보드 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `DashboardResponse`<br><br>**DashboardResponse 구조:**<br>- `studentProfile` (StudentProfileResponse): 학생 기본 정보<br>- `todaySolvedCount` (Int): 오늘 푼 문제 수<br>- `todaySolvedProblems` (List<TodaySolvedProblemResponse>): 오늘 푼 문제 목록<br>- `quote` (QuoteResponse, nullable): 랜덤 명언 (없으면 null)<br><br>**StudentProfileResponse 구조:**<br>- `nickname` (String): 닉네임<br>- `bojId` (String): BOJ ID<br>- `currentTier` (String): 현재 티어명 (예: "BRONZE")<br>- `currentTierLevel` (Int): 현재 티어의 Solved.ac 레벨 값<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br><br>**TodaySolvedProblemResponse 구조:**<br>- `problemId` (String): 문제 ID<br>- `result` (String): 풀이 결과 ("SUCCESS", "FAIL", "TIME_OVER")<br>- `solvedAt` (LocalDateTime): 풀이 일시 (ISO 8601 형식)<br><br>**QuoteResponse 구조:**<br>- `id` (String): 명언 ID<br>- `content` (String): 명언 내용<br>- `author` (String): 저자명 | JWT Token |

**예시 요청:**
```http
GET /api/v1/dashboard
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답:**
```json
{
  "studentProfile": {
    "nickname": "testuser",
    "bojId": "testuser123",
    "currentTier": "BRONZE",
    "currentTierLevel": 3,
    "consecutiveSolveDays": 5
  },
  "todaySolvedCount": 2,
  "todaySolvedProblems": [
    {
      "problemId": "1000",
      "result": "SUCCESS",
      "solvedAt": "2024-01-15T10:30:00"
    },
    {
      "problemId": "1001",
      "result": "SUCCESS",
      "solvedAt": "2024-01-15T09:15:00"
    }
  ],
  "quote": {
    "id": "quote-id-1",
    "content": "코딩은 90%의 디버깅과 10%의 버그 생성으로 이루어진다.",
    "author": "Unknown"
  }
}
```

---

## StudentController

학생 프로필 관리 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| PATCH | `/api/v1/students/me` | 학생의 닉네임 및 비밀번호를 수정합니다. 닉네임과 비밀번호를 선택적으로 변경할 수 있으며, 비밀번호 변경 시 현재 비밀번호 검증이 필요합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`UpdateProfileRequest`<br>- `nickname` (String, optional): 변경할 닉네임<br>  - 유효성: `@Size(min=2, max=20)` (2자 이상 20자 이하)<br>  - null이면 변경하지 않음<br>- `currentPassword` (String, optional): 현재 비밀번호<br>  - 비밀번호 변경 시 필수 입력<br>- `newPassword` (String, optional): 새로운 비밀번호<br>  - 유효성: `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: AuthController의 비밀번호 정책과 동일<br>  - null이면 변경하지 않음 | `204 No Content` (성공 시) | JWT Token |

**예시 요청 (닉네임만 변경):**
```http
PATCH /api/v1/students/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "nickname": "newNickname"
}
```

**예시 요청 (비밀번호만 변경):**
```http
PATCH /api/v1/students/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "currentPassword": "currentPassword123",
  "newPassword": "newPassword123!"
}
```

**예시 요청 (닉네임과 비밀번호 모두 변경):**
```http
PATCH /api/v1/students/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "nickname": "newNickname",
  "currentPassword": "currentPassword123",
  "newPassword": "newPassword123!"
}
```

**예시 응답 (성공):**
```
204 No Content
```

**에러 응답 예시 (닉네임 중복):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "DUPLICATE_NICKNAME",
  "message": "이미 사용 중인 닉네임입니다. nickname=newNickname"
}
```

**에러 응답 예시 (현재 비밀번호 불일치):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "PASSWORD_MISMATCH",
  "message": "현재 비밀번호가 일치하지 않습니다."
}
```

**에러 응답 예시 (현재 비밀번호 없이 새 비밀번호 변경 시도):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "비밀번호를 변경하려면 현재 비밀번호를 입력해야 합니다."
}
```

**에러 응답 예시 (비밀번호 정책 위반):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PASSWORD",
  "message": "영문, 숫자, 특수문자 3종류 이상 조합 시 최소 8자리 이상이어야 합니다."
}
```

---

## QuoteController

명언 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/quotes/random` | DB에 저장된 명언 중 하나를 무작위로 반환합니다. | 없음 | `QuoteResponse`<br><br>**QuoteResponse 구조:**<br>- `id` (String): 명언 ID<br>- `content` (String): 명언 내용<br>- `author` (String): 저자명<br><br>DB에 명언이 없으면 `204 No Content` 응답 | None |

**예시 요청:**
```http
GET /api/v1/quotes/random
```

**예시 응답:**
```json
{
  "id": "quote-id-1",
  "content": "코딩은 90%의 디버깅과 10%의 버그 생성으로 이루어진다.",
  "author": "Unknown"
}
```

**예시 응답 (명언 없음):**
```
204 No Content
```

---

## StatisticsController

통계 관련 API를 제공합니다. 무거운 통계 데이터를 별도로 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/statistics` | 학생의 월별 잔디(Heatmap), 카테고리별 분포, 누적 풀이 수를 포함한 통계 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `StatisticsResponse`<br><br>**StatisticsResponse 구조:**<br>- `monthlyHeatmap` (List<HeatmapDataResponse>): 최근 12개월간의 월별 잔디 데이터<br>- `categoryDistribution` (Map<String, Int>): 카테고리별 풀이 통계 (현재는 빈 맵, 향후 구현 예정)<br>- `totalSolvedCount` (Int): 누적 풀이 수<br><br>**HeatmapDataResponse 구조:**<br>- `date` (String): 날짜 (ISO 8601 형식, 예: "2024-01-15")<br>- `count` (Int): 해당 날짜의 풀이 수 | JWT Token |

**예시 요청:**
```http
GET /api/v1/statistics
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답:**
```json
{
  "monthlyHeatmap": [
    {
      "date": "2024-01-15",
      "count": 3
    },
    {
      "date": "2024-01-16",
      "count": 2
    },
    {
      "date": "2024-01-17",
      "count": 1
    }
  ],
  "categoryDistribution": {},
  "totalSolvedCount": 150
}
```

---

## LeaderboardController

랭킹 조회 관련 API를 제공합니다. Rating(점수) 기준 상위 사용자들의 랭킹을 조회할 수 있습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/ranks` | Rating(점수) 기준 상위 100명의 랭킹을 조회합니다. 동점자 처리: 점수가 같을 경우 먼저 가입한 순서로 정렬합니다. | 없음 | `List<LeaderboardResponse>`<br><br>**LeaderboardResponse 구조:**<br>- `rank` (Int): 순위 (1부터 시작)<br>- `nickname` (String): 닉네임<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값)<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br>- `profileImageUrl` (String, nullable): 프로필 이미지 URL (향후 확장용, 현재는 null) | None |

**예시 요청:**
```http
GET /api/v1/ranks
```

**예시 응답:**
```json
[
  {
    "rank": 1,
    "nickname": "topuser",
    "tier": "DIAMOND",
    "tierLevel": 23,
    "rating": 3500,
    "consecutiveSolveDays": 30,
    "profileImageUrl": null
  },
  {
    "rank": 2,
    "nickname": "seconduser",
    "tier": "PLATINUM",
    "tierLevel": 18,
    "rating": 2000,
    "consecutiveSolveDays": 15,
    "profileImageUrl": null
  },
  {
    "rank": 3,
    "nickname": "thirduser",
    "tier": "GOLD",
    "tierLevel": 13,
    "rating": 1200,
    "consecutiveSolveDays": 7,
    "profileImageUrl": null
  }
]
```

**예시 응답 (랭킹이 비어있는 경우):**
```json
[]
```

---

## 공통 사항

### Base URL
```
http://localhost:8080
```

### Content-Type
- Request: `application/json`
- Response: `application/json`

### 인증
JWT 토큰 기반 인증을 지원합니다.

**인증이 필요한 API:**
- 향후 특정 API에 JWT 토큰 인증이 적용될 예정입니다.

**JWT 토큰 사용 방법:**
1. `/api/v1/auth/signup` 또는 `/api/v1/auth/login`을 통해 토큰을 발급받습니다.
2. 인증이 필요한 API 요청 시 `Authorization` 헤더에 토큰을 포함합니다:
   ```
   Authorization: Bearer {token}
   ```
3. 토큰은 기본적으로 24시간 동안 유효합니다 (설정 가능).

**토큰 구조:**
- JWT 토큰의 `subject` (sub) 클레임에는 BOJ ID가 저장됩니다.
- 토큰은 HMAC SHA-256 알고리즘으로 서명됩니다.

### 에러 응답 형식
모든 예외 발생 시 아래의 통일된 JSON 포맷으로 응답합니다:
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "content: 회고 내용은 10자 이상이어야 합니다."
}
```

**ErrorResponse 필드 설명:**
- `status` (Int): HTTP 상태 코드 (400, 404, 500 등)
- `error` (String): HTTP 상태 코드에 해당하는 에러 이름 (예: "Bad Request", "Not Found", "Internal Server Error")
- `code` (String): 애플리케이션 내부 에러 코드 (프론트엔드에서 구체적인 예외 처리를 위해 사용)
- `message` (String): 사용자에게 표시할 에러 메시지

**주요 에러 코드:**
- `COMMON_INVALID_INPUT` (400): 입력값이 올바르지 않음
- `COMMON_VALIDATION_FAILED` (400): 유효성 검사 실패
- `INVALID_PASSWORD` (400): 비밀번호 정책 위반 (복잡도 검증 실패)
- `COMMON_RESOURCE_NOT_FOUND` (404): 요청한 자원을 찾을 수 없음
- `STUDENT_NOT_FOUND` (404): 학생을 찾을 수 없음
- `PROBLEM_NOT_FOUND` (404): 문제를 찾을 수 없음
- `RETROSPECTIVE_NOT_FOUND` (404): 회고를 찾을 수 없음
- `COMMON_INTERNAL_ERROR` (500): 서버 내부 오류

**예시 에러 응답:**

유효성 검사 실패 (400):
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "content: 회고 내용은 10자 이상이어야 합니다."
}
```

리소스 없음 (404):
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "RETROSPECTIVE_NOT_FOUND",
  "message": "회고를 찾을 수 없습니다."
}
```

### 유효성 검사 실패 시
- `@NotBlank`, `@NotNull` 위반: 400 Bad Request (`COMMON_VALIDATION_FAILED`)
- `@Size`, `@Positive` 위반: 400 Bad Request (`COMMON_VALIDATION_FAILED`)
- 존재하지 않는 리소스 조회: 404 Not Found (해당 리소스에 맞는 에러 코드, 예: `STUDENT_NOT_FOUND`, `PROBLEM_NOT_FOUND`, `RETROSPECTIVE_NOT_FOUND`)

### 날짜/시간 형식
모든 날짜/시간 필드는 ISO 8601 형식을 따릅니다:
- 예: `2024-01-15T10:30:00`

---

## 참고사항

### Tier Enum 값
티어는 Solved.ac의 Rating(점수)을 기반으로 자동 계산됩니다.

- `UNRATED`: 0점 (Unrated)
- `BRONZE`: 30점 이상 (Solved.ac 레벨 1-5, 대표값: 3)
- `SILVER`: 200점 이상 (Solved.ac 레벨 6-10, 대표값: 8)
- `GOLD`: 800점 이상 (Solved.ac 레벨 11-15, 대표값: 13)
- `PLATINUM`: 1600점 이상 (Solved.ac 레벨 16-20, 대표값: 18)
- `DIAMOND`: 3000점 이상 (Solved.ac 레벨 21-25, 대표값: 23)
- `RUBY`: 5000점 이상 (Solved.ac 레벨 26-30, 대표값: 28)

**예시:**
- Rating 1223점 → `GOLD` 티어 (800점 이상이므로)
- Rating 650점 → `SILVER` 티어 (200점 이상이지만 800점 미만)
- Rating 0점 → `UNRATED` 티어

### ProblemResult Enum 값
- `SUCCESS`: 풀이 성공
- `FAIL`: 풀이 실패
- `TIME_OVER`: 시간 초과

### Swagger UI
API 문서는 Swagger UI를 통해 확인할 수 있습니다:
```
http://localhost:8080/swagger-ui.html
```

