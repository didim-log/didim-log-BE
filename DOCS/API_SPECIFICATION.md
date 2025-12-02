# DidimLog API 명세서

이 문서는 DidimLog 프로젝트의 모든 REST API 엔드포인트를 정리한 명세서입니다.

## 목차

- [ProblemController](#problemcontroller)
- [StudyController](#studycontroller)
- [RetrospectiveController](#retrospectivecontroller)
- [DashboardController](#dashboardcontroller)

---

## ProblemController

문제 추천 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/problems/recommend` | 학생의 현재 티어보다 한 단계 높은 난이도의 문제 중, 아직 풀지 않은 문제를 추천합니다. | **Query Parameters:**<br>- `studentId` (String, required): 학생 ID<br>- `count` (Int, optional, default: 1): 추천할 문제 개수<br>  - 유효성: `@Positive` (1 이상) | `List<ProblemResponse>`<br><br>**ProblemResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 문제 카테고리<br>- `difficulty` (String): 난이도 티어명 (예: "BRONZE", "SILVER")<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL | None |

**예시 요청:**
```http
GET /api/v1/problems/recommend?studentId=student-123&count=3
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
| POST | `/api/v1/study/submit` | 학생이 문제를 풀고 결과를 제출합니다. 풀이 결과가 Solutions에 저장됩니다. | **Query Parameters:**<br>- `studentId` (String, required): 학생 ID<br><br>**Request Body:**<br>`SolutionSubmitRequest`<br>- `problemId` (String, required): 문제 ID<br>  - 유효성: `@NotBlank`<br>- `timeTaken` (Long, required): 풀이 소요 시간 (초 단위)<br>  - 유효성: `@NotNull`, `@Positive` (0보다 커야 함)<br>- `isSuccess` (Boolean, required): 풀이 성공 여부<br>  - 유효성: `@NotNull` | `SolutionSubmitResponse`<br><br>**SolutionSubmitResponse 구조:**<br>- `message` (String): 응답 메시지 ("문제 풀이 결과가 저장되었습니다.")<br>- `currentTier` (String): 현재 티어명 (예: "BRONZE", "SILVER")<br>- `currentTierLevel` (Int): 현재 티어의 Solved.ac 레벨 값 | None |

**예시 요청:**
```http
POST /api/v1/study/submit?studentId=student-123
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
| POST | `/api/v1/retrospectives` | 학생이 문제 풀이 후 회고를 작성합니다. 이미 해당 문제에 대한 회고가 있으면 수정됩니다. | **Query Parameters:**<br>- `studentId` (String, required): 학생 ID<br>- `problemId` (String, required): 문제 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>- `id` (String): 회고 ID<br>- `studentId` (String): 학생 ID<br>- `problemId` (String): 문제 ID<br>- `content` (String): 회고 내용<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식) | None |
| GET | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 조회합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>(위와 동일) | None |
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

대시보드 정보 조회 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/dashboard` | 학생의 현재 티어, 최근 풀이 기록, 추천 문제를 포함한 대시보드 정보를 조회합니다. | **Query Parameters:**<br>- `studentId` (String, required): 학생 ID | `DashboardResponse`<br><br>**DashboardResponse 구조:**<br>- `currentTier` (String): 현재 티어명 (예: "BRONZE")<br>- `currentTierLevel` (Int): 현재 티어의 Solved.ac 레벨 값<br>- `recentSolutions` (List<SolutionResponse>): 최근 풀이 기록 (최대 10개, 최신순)<br>- `recommendedProblems` (List<ProblemResponse>): 추천 문제 목록 (기본 3개)<br><br>**SolutionResponse 구조:**<br>- `problemId` (String): 문제 ID<br>- `timeTaken` (Long): 풀이 소요 시간 (초)<br>- `result` (String): 풀이 결과 ("SUCCESS", "FAIL", "TIME_OVER")<br>- `solvedAt` (LocalDateTime): 풀이 일시 (ISO 8601 형식)<br><br>**ProblemResponse 구조:**<br>(ProblemController 섹션 참고) | None |

**예시 요청:**
```http
GET /api/v1/dashboard?studentId=student-123
```

**예시 응답:**
```json
{
  "currentTier": "BRONZE",
  "currentTierLevel": 3,
  "recentSolutions": [
    {
      "problemId": "1000",
      "timeTaken": 120,
      "result": "SUCCESS",
      "solvedAt": "2024-01-15T10:30:00"
    },
    {
      "problemId": "1001",
      "timeTaken": 90,
      "result": "SUCCESS",
      "solvedAt": "2024-01-15T09:15:00"
    }
  ],
  "recommendedProblems": [
    {
      "id": "2000",
      "title": "두 수의 합",
      "category": "MATH",
      "difficulty": "SILVER",
      "difficultyLevel": 6,
      "url": "https://www.acmicpc.net/problem/2000"
    }
  ]
}
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
현재 모든 API는 인증이 필요하지 않습니다. (향후 JWT 토큰 기반 인증이 추가될 예정)

### 에러 응답 형식
에러 발생 시 Spring Boot 기본 에러 응답 형식을 따릅니다:
```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "회고 내용은 10자 이상이어야 합니다.",
  "path": "/api/v1/retrospectives"
}
```

### 유효성 검사 실패 시
- `@NotBlank`, `@NotNull` 위반: 400 Bad Request
- `@Size`, `@Positive` 위반: 400 Bad Request
- 존재하지 않는 리소스 조회: 400 Bad Request (IllegalArgumentException)

### 날짜/시간 형식
모든 날짜/시간 필드는 ISO 8601 형식을 따릅니다:
- 예: `2024-01-15T10:30:00`

---

## 참고사항

### Tier Enum 값
- `BRONZE`: Solved.ac 레벨 1-5
- `SILVER`: Solved.ac 레벨 6-10
- `GOLD`: Solved.ac 레벨 11-15
- `PLATINUM`: Solved.ac 레벨 16-20
- `DIAMOND`: Solved.ac 레벨 21-25
- `RUBY`: Solved.ac 레벨 26-30

### ProblemResult Enum 값
- `SUCCESS`: 풀이 성공
- `FAIL`: 풀이 실패
- `TIME_OVER`: 시간 초과

### Swagger UI
API 문서는 Swagger UI를 통해 확인할 수 있습니다:
```
http://localhost:8080/swagger-ui.html
```

