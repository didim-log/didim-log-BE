# DidimLog API 명세서

이 문서는 DidimLog 프로젝트의 모든 REST API 엔드포인트를 정리한 명세서입니다.

## 목차

- [AuthController](#authcontroller)
- [OAuth2 Authentication](#oauth2-authentication)
- [AiAnalysisController](#aianalysiscontroller)
- [ProblemController](#problemcontroller)
- [StudyController](#studycontroller)
- [RetrospectiveController](#retrospectivecontroller)
- [DashboardController](#dashboardcontroller)
- [LogController](#logcontroller)
- [StudentController](#studentcontroller)
- [QuoteController](#quotecontroller)
- [StatisticsController](#statisticscontroller)
- [RankingController](#rankingcontroller)
- [AdminController](#admincontroller)
- [AdminDashboardController](#admindashboardcontroller)
- [ProblemCollectorController](#problemcollectorcontroller)
- [FeedbackController](#feedbackcontroller)
- [TemplateController](#templatecontroller)
- [MemberController](#membercontroller)
- [NoticeController](#noticecontroller)
- [PublicSystemController](#publicsystemcontroller)
- [AdminAuditController](#adminauditcontroller)
- [Swagger 카테고리 통합 기준](#swagger-카테고리-통합-기준)

---

## Swagger 카테고리 통합 기준

Swagger UI에서 카테고리를 기능군 기준으로 통합합니다.

- `Auth`: 인증/인가
- `Template`: 회고 템플릿 관리/렌더링
- `Retrospective`: 회고 CRUD/검색
- `Log`: 코드 로그 작성/AI 리뷰/피드백
- `Problem`, `Study`, `Statistics`, `Ranking`, `Dashboard`: 학습/분석 기능
- `Admin`: 관리자 기능 전체(회원/로그/문제수집/시스템제어/감사로그 포함)
- `System`: 공개 시스템 상태 조회
- `Member`, `Student`, `Notice`, `Feedback`, `Quote`: 도메인별 기능

---

## AuthController

인증 관련 API를 제공합니다. Solved.ac 연동 기반의 회원가입 및 JWT 토큰 기반 로그인을 지원합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/auth/signup` | BOJ 프로필 상태 메시지 인증을 마친 세션과 BOJ ID를 확인한 뒤 계정을 생성하고 JWT 토큰을 발급합니다. 인증 세션은 가입 시 한 번만 사용할 수 있습니다. | **Request Body:**<br>`SignupRequest`<br>- `bojId` (String, required): 인증한 BOJ ID<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)`<br>  - 영문·숫자·특수문자 조합 정책 적용<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email`<br>- `verificationSessionId` (String, required): `/boj/code`에서 발급받아 `/boj/verify`를 통과한 세션 ID | `AuthResponse`<br><br>- `token` (String): JWT Access Token<br>- `refreshToken` (String): Refresh Token<br>- `message` (String): 응답 메시지<br>- `rating` (Int): Solved.ac Rating<br>- `tier` (String): 티어명<br>- `tierLevel` (Int): 티어 레벨 | None |
| POST | `/api/v1/auth/login` | BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생합니다. 로그인 시 Solved.ac API를 통해 Rating 및 Tier 정보를 동기화합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상) | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token<br>- `message` (String): 응답 메시지 ("로그인에 성공했습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| GET | `/api/v1/auth/check-duplicate` | 회원가입 전 BOJ ID 중복 여부를 조회합니다. | **Query Parameters:**<br>- `bojId` (String, required) | `BojIdDuplicateCheckResponse`<br>- `isDuplicate` (Boolean)<br>- `message` (String) | None |
| POST | `/api/v1/auth/super-admin` | 관리자 키(adminKey)를 입력받아 검증 후 ADMIN 권한으로 계정을 생성하고 JWT 토큰을 발급합니다. 이 API는 초기 관리자 생성을 위해 permitAll로 열려있습니다. | **Request Body:**<br>`SuperAdminRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: signup API와 동일<br>- `adminKey` (String, required): 관리자 생성용 보안 키<br>  - 유효성: `@NotBlank`<br>  - 환경변수 `ADMIN_SECRET_KEY`와 일치해야 함 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (ADMIN role 포함)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/signup/finalize` | 서버가 확인한 OAuth 가입 정보와 연결되지 않은 기존 경로이므로 현재 제공하지 않습니다. | 없음 | `410 Gone` | None |
| POST | `/api/v1/auth/find-account` | 이메일을 입력받아 가입된 소셜 제공자(Provider)를 반환합니다. | **Request Body:**<br>`FindAccountRequest`<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email` | `FindAccountResponse`<br>- `provider` (String)<br>- `message` (String) | None |
| POST | `/api/v1/auth/find-id` | 이메일을 입력받아 BOJ ID를 조회합니다. | **Request Body:**<br>`FindIdRequest`<br>- `email` (String, required)<br>  - 유효성: `@NotBlank`, `@Email` | `FindIdPasswordResponse`<br>- `message` (String) | None |
| POST | `/api/v1/auth/find-password` | 이메일과 BOJ ID를 검증한 뒤 비밀번호 재설정 코드를 발급하고 이메일로 전송합니다. | **Request Body:**<br>`FindPasswordRequest`<br>- `email` (String, required)<br>  - 유효성: `@NotBlank`, `@Email`<br>- `bojId` (String, required)<br>  - 유효성: `@NotBlank` | `FindIdPasswordResponse`<br>- `message` (String) | None |
| POST | `/api/v1/auth/reset-password` | 비밀번호 재설정 코드와 새 비밀번호로 비밀번호를 변경합니다. | **Request Body:**<br>`ResetPasswordRequest`<br>- `resetCode` (String, required)<br>  - 유효성: `@NotBlank`<br>- `newPassword` (String, required)<br>  - 유효성: `@NotBlank`, `@Size(min=8)` | `FindIdPasswordResponse`<br>- `message` (String) | None |
| POST | `/api/v1/auth/boj/code` | BOJ 프로필 상태 메시지 인증에 사용할 코드를 발급합니다. | 없음 | `BojCodeIssueResponse`<br>- `sessionId` (String)<br>- `code` (String)<br>- `expiresInSeconds` (Long) | None |
| POST | `/api/v1/auth/boj/verify` | BOJ 프로필 상태 메시지에서 발급 코드 포함 여부를 확인합니다. 성공한 세션은 `/signup`에서 한 번만 사용할 수 있습니다. | **Request Body:**<br>`BojVerifyRequest`<br>- `sessionId` (String, required)<br>- `bojId` (String, required) | `BojVerifyResponse`<br>- `verified` (Boolean)<br>- `verifiedBojId` (String) | None |
| POST | `/api/v1/auth/refresh` | Refresh Token으로 Access/Refresh Token을 재발급합니다. | **Headers:**<br>- `Authorization: Bearer {refreshToken}` (optional)<br>**Request Body:**<br>`RefreshTokenRequest`<br>- `refreshToken` (String, optional)<br><br>Body와 Header 중 하나에서 Refresh Token 제공 필요 | `AuthResponse`<br>- `token` (String)<br>- `refreshToken` (String)<br>- `message` (String) | None |

**예시 요청 (회원가입):**
```http
POST /api/v1/auth/signup
Content-Type: application/json

{
  "bojId": "user123",
  "password": "securePassword123",
  "email": "user@example.com",
  "verificationSessionId": "6aaf55ee-9ee4-4aaa-9d0d-9c831ae84ef6"
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

**예시 요청 (슈퍼 관리자 생성):**
```http
POST /api/v1/auth/super-admin
Content-Type: application/json

{
  "bojId": "admin123",
  "password": "securePassword123!",
  "adminKey": "your-admin-secret-key"
}
```

**예시 응답 (슈퍼 관리자 생성):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbjEyMyIsInJvbGUiOiJBRE1JTiIsImlhdCI6MTYxNjIzOTAyMiwiZXhwIjoxNjE2MzI1NDIyfQ.signature",
  "message": "회원가입이 완료되었습니다.",
  "rating": 1500,
  "tier": "PLATINUM",
  "tierLevel": 18
}
```

**에러 응답 예시 (관리자 키 불일치):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "관리자 키가 일치하지 않습니다."
}
```

**중단된 가입 마무리 경로:**
```http
POST /api/v1/auth/signup/finalize

HTTP/1.1 410 Gone
```

**에러 응답 예시 (닉네임 중복):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "이미 사용 중인 닉네임입니다. nickname=newuser"
}
```

**에러 응답 예시 (이미 가입된 계정):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "이미 가입된 계정입니다. provider=GOOGLE, providerId=123456789"
}
```

**에러 응답 예시 (이미 가입된 BOJ ID):**
```json
{
  "status": 409,
  "error": "Conflict",
  "code": "DUPLICATE_BOJ_ID",
  "message": "이미 가입된 백준 아이디입니다."
}
```

---

## OAuth2 Authentication

OAuth2 소셜 로그인을 지원합니다. Google, GitHub, Naver를 통한 소셜 로그인이 가능합니다.

### OAuth2 로그인 플로우

1. **소셜 로그인 시작**: 프론트엔드에서 `/oauth2/authorization/{provider}` 엔드포인트로 리다이렉트
2. **소셜 로그인 인증**: 각 공급자(Google/GitHub/Naver)의 인증 페이지로 이동
3. **콜백 처리**: 인증 성공 후 백엔드가 프론트엔드 콜백 URL로 리다이렉트
4. **토큰 전달**: JWT 토큰이 쿼리 파라미터로 전달됨

### 지원하는 공급자 (Provider)

- **Google**: `/oauth2/authorization/google`
- **GitHub**: `/oauth2/authorization/github`
- **Naver**: `/oauth2/authorization/naver`

### 콜백 처리

인증 성공 시 백엔드는 프론트엔드 콜백 URL로 리다이렉트하며, 다음 쿼리 파라미터를 포함합니다:

**기존 유저 (성공 시):**
- `token` (String, required): JWT Access Token
- `isNewUser` (Boolean, required): `false`

**신규 유저 (성공 시):**
- `isNewUser` (Boolean, required): `true`
- `email` (String, required): 소셜 계정 이메일 (없으면 빈 문자열)
- `provider` (String, required): 소셜 로그인 제공자 (예: `google`, `github`, `naver`)
- `providerId` (String, required): 제공자별 사용자 ID

**실패 시:**
- `error` (String, required): 에러 코드
- `error_description` (String, optional): 에러 설명

**예시 URL (기존 유저 - 성공):**
```
http://localhost:5173/oauth/callback?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...&isNewUser=false
```

**예시 URL (신규 유저 - 성공):**
```
http://localhost:5173/oauth/callback?isNewUser=true&email=user@example.com&provider=google&providerId=123456789
```

**예시 URL (신규 유저 - 이메일 미제공 케이스):**
```
http://localhost:5173/oauth/callback?isNewUser=true&email=&provider=github&providerId=123456789
```

**예시 URL (실패):**
```
http://localhost:5173/oauth/callback?error=access_denied&error_description=사용자가%20인증을%20거부했습니다
```

### 설정

- **콜백 URL**: 환경 변수 `app.oauth.redirect-uri`로 설정 (기본값: `http://localhost:5173/oauth/callback`)
- **인증 경로**: `/oauth2/**` 경로는 인증 없이 접근 가능 (`permitAll`)

### OAuth 가입 범위

- 이미 연결된 소셜 계정은 OAuth 인증 성공 후 JWT를 발급받습니다.
- 신규 소셜 가입은 서버가 발급한 가입 표식으로 provider 정보를 확인하는 절차가 아직 없어 제공하지 않습니다.
- 기존 `/api/v1/auth/signup/finalize`는 요청 본문의 provider 정보를 신뢰하지 않도록 `410 Gone`을 반환합니다.
- 신규 사용자는 BOJ 상태 메시지 인증 후 `/api/v1/auth/signup`으로 가입합니다.

---

## AiAnalysisController

AI 분석 관련 API를 제공합니다. 서비스 내부 섹션 기준으로 마크다운을 생성합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|

> 현재 서버 구현 기준으로 `/api/v1/ai/analyze` 엔드포인트는 제공하지 않습니다.

---

## ProblemController

문제 추천 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/problems/recommend` | 학생의 현재 티어 범위(-2~+2)에서 아직 풀지 않은 문제를 추천합니다. `category`/`language` 필터를 지원하며, `filterMode` 정책에 따라 카테고리 확장 범위를 제어할 수 있습니다. `language=ko`는 본문/입출력/제목 기반 strict 필터를 적용합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `count` (Int, optional, default: 10): 추천할 문제 개수<br>  - 유효성: `@Min(1)`, `@Max(50)`<br>- `category` (String, optional): 문제 카테고리 필터<br>  - 축약형/Enum/영문 정식명 모두 허용 (예: `BFS`, `IMPLEMENTATION`, `Graph Theory`)<br>- `filterMode` (String, optional, default: `RELATED`): 카테고리 필터 정책<br>  - `EXACT`: 대표 카테고리 정확 일치만 허용<br>  - `HIERARCHY`: 대표 카테고리 + 하위 태그 확장<br>  - `RELATED`: `HIERARCHY` + 부모/형제 카테고리까지 확장<br>- `language` (String, optional): 문제 언어 필터 (`ko` 또는 `en`)<br>  - `ko`: 영어 본문 문제를 제외하는 strict 필터 적용 | `List<ProblemResponse>`<br><br>**ProblemResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 대표 카테고리(영문 표준명)<br>- `primaryCategory` (String): 프론트 표시용 대표 전략 카테고리(Enum 이름, 예: `BFS`, `DP`)<br>- `secondaryCategories` (List<String>): 보조 전략 카테고리(Enum 이름 리스트)<br>- `normalizedTags` (List<String>): 정규화된 태그(Enum 이름 리스트)<br>- `difficulty` (String): 난이도 티어명 (예: `BRONZE`, `SILVER`)<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL<br>- `language` (String): 문제 언어 (`ko`/`en`)<br>- `matchedByPrimary` (Boolean, nullable): 카테고리 필터 시 대표 카테고리 매칭 여부<br>- `matchedByTags` (Boolean, nullable): 카테고리 필터 시 태그 확장 매칭 여부<br>- `expandedFrom` (List<String>): 카테고리 확장 매칭 근거(영문 표준명) | JWT Token |
| GET | `/api/v1/problems/{problemId}` | 문제 상세 정보를 조회합니다. DB에 상세 정보가 없으면 크롤링으로 보강 후 반환합니다. | **Path Variables:**<br>- `problemId` (Long, required): 문제 ID (`@Positive`) | `ProblemDetailResponse`<br><br>**ProblemDetailResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 대표 카테고리(영문 표준명)<br>- `primaryCategory` (String): 대표 전략 카테고리(Enum 이름)<br>- `secondaryCategories` (List<String>): 보조 전략 카테고리(Enum 이름 리스트)<br>- `normalizedTags` (List<String>): 정규화된 태그(Enum 이름 리스트)<br>- `difficulty` (String): 난이도 티어명<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL<br>- `descriptionHtml` (String, nullable): 문제 본문 HTML<br>- `inputDescriptionHtml` (String, nullable): 입력 설명 HTML<br>- `outputDescriptionHtml` (String, nullable): 출력 설명 HTML<br>- `sampleInputs` (List<String>, nullable): 샘플 입력<br>- `sampleOutputs` (List<String>, nullable): 샘플 출력<br>- `tags` (List<String>): 원본 태그(영문 표준명)<br>- `language` (String): 문제 언어 (`ko`/`en`) | None |
| GET | `/api/v1/problems/search` | 문제 번호로 문제를 검색합니다. DB에 없으면 메타데이터 조회/상세 크롤링 후 저장하여 반환합니다. | **Query Parameters:**<br>- `q` (Long, required): 문제 번호 (`@Positive`) | `ProblemDetailResponse` | None |
| GET | `/api/v1/problems/categories/meta` | 카테고리 정규화 메타 정보를 조회합니다. FE에서 카테고리 선택/표시/연관 추천 UX를 동기화할 때 사용합니다. | 없음 | `List<ProblemCategoryMetaResponse>`<br><br>**ProblemCategoryMetaResponse 구조:**<br>- `canonical` (String): 카테고리 canonical(enum name)<br>- `englishName` (String): 영문 표준명<br>- `koreanName` (String): 한글 표기<br>- `aliases` (List<String>): 허용 별칭 목록<br>- `parents` (List<String>): 부모 카테고리 canonical 목록<br>- `children` (List<String>): 하위 카테고리 canonical 목록<br>- `related` (List<String>): 연관 카테고리 canonical 목록 | None |

**예시 요청 (카테고리 연관 확장 추천):**
```http
GET /api/v1/problems/recommend?count=3&category=DFS&filterMode=RELATED
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (카테고리별 추천):**
```http
GET /api/v1/problems/recommend?count=5&category=IMPLEMENTATION
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (한국어 strict 필터):**
```http
GET /api/v1/problems/recommend?category=DFS&language=ko&count=10&filterMode=RELATED
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답:**
```json
[
  {
    "id": "1000",
    "title": "A+B",
    "category": "Implementation",
    "primaryCategory": "IMPLEMENTATION",
    "secondaryCategories": [],
    "normalizedTags": ["IMPLEMENTATION"],
    "difficulty": "BRONZE",
    "difficultyLevel": 3,
    "url": "https://www.acmicpc.net/problem/1000",
    "language": "ko",
    "matchedByPrimary": true,
    "matchedByTags": true,
    "expandedFrom": ["Depth-first Search", "Graph Theory"]
  },
  {
    "id": "1001",
    "title": "A-B",
    "category": "Implementation",
    "primaryCategory": "IMPLEMENTATION",
    "secondaryCategories": [],
    "normalizedTags": ["IMPLEMENTATION"],
    "difficulty": "BRONZE",
    "difficultyLevel": 3,
    "url": "https://www.acmicpc.net/problem/1001",
    "language": "ko",
    "matchedByPrimary": true,
    "matchedByTags": false,
    "expandedFrom": ["Depth-first Search"]
  }
]
```

**예시 요청 (문제 상세 조회):**
```http
GET /api/v1/problems/1000
```

**예시 응답 (문제 상세 조회):**
```json
{
  "id": "1000",
  "title": "A+B",
  "category": "Implementation",
  "primaryCategory": "IMPLEMENTATION",
  "secondaryCategories": [],
  "normalizedTags": ["IMPLEMENTATION"],
  "difficulty": "BRONZE",
  "difficultyLevel": 3,
  "url": "https://www.acmicpc.net/problem/1000",
  "descriptionHtml": "<p>두 정수 A와 B를 입력받은 다음...</p>",
  "inputDescriptionHtml": "<p>첫째 줄에 A와 B가 주어진다.</p>",
  "outputDescriptionHtml": "<p>첫째 줄에 A+B를 출력한다.</p>",
  "sampleInputs": ["1 2"],
  "sampleOutputs": ["3"],
  "tags": ["Implementation"],
  "language": "ko"
}
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
| POST | `/api/v1/retrospectives` | 학생이 문제 풀이 후 회고를 작성합니다. 이미 해당 문제에 대한 회고가 있으면 수정됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `problemId` (String, required): 문제 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상)<br>- `summary` (String, required): 한 줄 요약<br>  - 유효성: `@NotBlank`, `@Size(max=200)`<br>- `resultType` (ProblemResult, optional): 풀이 결과 타입 (SUCCESS/FAIL/TIME_OVER)<br>- `solvedCategory` (String, optional): 풀이 전략 태그 (`@Size(max=50)`)<br>- `solveTime` (String, optional): 풀이 소요 시간 (`@Size(max=50)`) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>- `id` (String): 회고 ID<br>- `studentId` (String): 회고 소유 학생 ID<br>- `isOwner` (Boolean): 현재 요청 사용자의 소유 여부<br>- `problemId` (String): 문제 ID<br>- `problemTitle` (String, nullable): 문제 제목 (리스트/카드 렌더링 최적화용)<br>- `content` (String): 회고 내용<br>- `summary` (String, nullable): 한 줄 요약<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601)<br>- `isBookmarked` (Boolean): 북마크 여부<br>- `mainCategory` (String, nullable): 주요 카테고리<br>- `solutionResult` (String, nullable): 풀이 결과<br>- `solvedCategory` (String, nullable): 풀이 전략 태그<br>- `solveTime` (String, nullable): 풀이 소요 시간 | JWT Token |
| GET | `/api/v1/retrospectives` | 인증 사용자의 회고 목록을 조회합니다. 키워드, 카테고리, 북마크, 정렬, 페이징 필터를 지원합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br><br>**Query Parameters:**<br>- `keyword` (String, optional): 내용/문제 ID 검색어<br>- `category` (String, optional): 카테고리 필터<br>- `solvedCategory` (String, optional): 풀이 전략 태그(부분 일치)<br>- `isBookmarked` (Boolean, optional): 북마크 필터<br>- `page` (Int, optional, default: 1): `@Min(1)`<br>- `size` (Int, optional, default: 10): `@Min(1)`, `@Max(100)`<br>- `sort` (String, optional): 예 `createdAt,desc` | `RetrospectivePageResponse`<br><br>**RetrospectivePageResponse 구조:**<br>- `content` (List<RetrospectiveResponse>)<br>- `totalElements` (Long)<br>- `totalPages` (Int)<br>- `currentPage` (Int)<br>- `size` (Int)<br>- `hasNext` (Boolean)<br>- `hasPrevious` (Boolean) | JWT Token |
| GET | `/api/v1/retrospectives/{retrospectiveId}` | 인증 사용자의 소유 회고를 조회합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br>**Path Variables:**<br>- `retrospectiveId` (String, required) | `RetrospectiveResponse` | JWT Token |
| GET | `/api/v1/retrospectives/template` | 결과 타입에 맞는 기본 템플릿을 렌더링해 조회합니다. 서버 기본 선택 규칙: `SUCCESS`는 성공 기본 템플릿, `FAIL`/`TIME_OVER`는 실패 기본 템플릿을 사용하며, 사용자 기본값이 없거나 깨진 경우 시스템 기본 템플릿으로 fallback 합니다. 문제 메타 조회 실패 시에도 최소 문제 정보로 렌더링된 문자열을 반환합니다. 렌더링은 `app.template.render-timeout-millis`(기본 4000ms) 제한을 가지며, 타임아웃 시 `app.template.timeout-fallback-enabled=true`이면 기본 템플릿 fallback 본문을 `200`으로 반환하고(`fallbackUsed=true`), 비활성화 시 `TEMPLATE_RENDER_TIMEOUT(504)`를 반환합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br>**Query Parameters:**<br>- `problemId` (Long, required): 문제 ID (`@Positive`)<br>- `resultType` (ProblemResult, required): SUCCESS/FAIL/TIME_OVER | `RetrospectiveTemplateResponse`<br>- `template` (String)<br>- `fallbackUsed` (Boolean): timeout fallback 적용 여부<br>- `fallbackReason` (String, nullable): fallback 사유 코드 (`TEMPLATE_RENDER_TIMEOUT`) | JWT Token |
| POST | `/api/v1/retrospectives/{retrospectiveId}/bookmark` | 인증 사용자의 소유 회고 북마크 상태를 토글합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br>**Path Variables:**<br>- `retrospectiveId` (String, required) | `BookmarkToggleResponse`<br>- `isBookmarked` (Boolean) | JWT Token |
| DELETE | `/api/v1/retrospectives/{retrospectiveId}` | 인증 사용자의 소유 회고를 삭제합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br>**Path Variables:**<br>- `retrospectiveId` (String, required) | `204 No Content` | JWT Token |

**예시 요청 (회고 작성 - 성공 케이스):**
```http
POST /api/v1/retrospectives?problemId=1000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다.",
  "summary": "두 수의 합을 구하는 기본 구현 문제",
  "resultType": "SUCCESS",
  "solvedCategory": "Implementation"
}
```

**예시 요청 (회고 작성 - 실패 케이스):**
```http
POST /api/v1/retrospectives?problemId=1000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "이 문제를 풀지 못했습니다. 시간 복잡도를 고려하지 못해서 시간 초과가 발생했습니다.",
  "summary": "시간 복잡도 고려 부족으로 실패",
  "resultType": "FAIL",
  "solvedCategory": "BruteForce"
}
```

**예시 요청 (회고 작성 - 최소 필수 필드만):**
```http
POST /api/v1/retrospectives?problemId=1000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
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
  "isOwner": true,
  "problemId": "1000",
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다.",
  "summary": "두 수의 합을 구하는 기본 구현 문제",
  "createdAt": "2024-01-15T10:30:00",
  "isBookmarked": false,
  "mainCategory": null,
  "solutionResult": "SUCCESS",
  "solvedCategory": "Implementation"
}
```

**예시 요청 (회고 목록 조회 - 기본):**
```http
GET /api/v1/retrospectives?page=1&size=10
```

**예시 요청 (회고 목록 조회 - 키워드 검색):**
```http
GET /api/v1/retrospectives?keyword=DFS&page=1&size=10
```

**예시 요청 (회고 목록 조회 - 카테고리 필터):**
```http
GET /api/v1/retrospectives?category=DFS&page=1&size=10
```

**예시 요청 (회고 목록 조회 - 북마크 필터):**
```http
GET /api/v1/retrospectives?isBookmarked=true&page=1&size=10
```

**예시 요청 (회고 목록 조회 - 정렬):**
```http
GET /api/v1/retrospectives?sort=createdAt,asc&page=1&size=10
```

**예시 응답 (회고 목록 조회):**
```json
{
  "content": [
    {
      "id": "retrospective-123",
      "studentId": "student-123",
      "isOwner": true,
      "problemId": "1000",
      "content": "이 문제는 DFS를 사용해서 풀었습니다.",
      "summary": "DFS를 활용한 그래프 탐색 문제",
      "createdAt": "2024-01-15T10:30:00",
      "isBookmarked": true,
      "mainCategory": "DFS",
      "solutionResult": "SUCCESS",
      "solvedCategory": "DFS"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 1,
  "size": 10,
  "hasNext": false,
  "hasPrevious": false
}
```

**예시 요청 (북마크 토글):**
```http
POST /api/v1/retrospectives/retrospective-123/bookmark
Authorization: Bearer <token>
```

**예시 응답 (북마크 토글):**
```json
{
  "isBookmarked": true
}
```

**예시 요청 (회고 삭제):**
```http
DELETE /api/v1/retrospectives/retrospective-123
Authorization: Bearer <token>
```

**예시 응답 (회고 삭제):**
```http
HTTP/1.1 204 No Content
```

---

## DashboardController

대시보드 정보 조회 API를 제공합니다. 오늘의 활동 중심으로 경량화된 정보를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/dashboard` | 학생의 오늘의 활동(오늘 푼 문제), 기본 프로필 정보, 랜덤 명언을 포함한 대시보드 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `DashboardResponse`<br><br>**DashboardResponse 구조:**<br>- `studentProfile` (StudentProfileResponse): 학생 기본 정보<br>- `todaySolvedCount` (Int): 오늘 푼 문제 수<br>- `todaySolvedProblems` (List<TodaySolvedProblemResponse>): 오늘 푼 문제 목록<br>- `quote` (QuoteResponse, nullable): 랜덤 명언 (없으면 null)<br>- `currentTierTitle` (String): 예: "Gold V"<br>- `nextTierTitle` (String): 예: "Gold IV" (최고 티어면 currentTierTitle과 동일)<br>- `currentRating` (Int): 현재 Solved.ac Rating<br>- `requiredRatingForNextTier` (Int): 다음 티어에 필요한 Rating(최고 티어면 기준값)<br>- `progressPercentage` (Int): 0~100<br><br>**StudentProfileResponse 구조:**<br>- `nickname` (String): 닉네임<br>- `bojId` (String): BOJ ID<br>- `currentTier` (String): 현재 티어명 (예: "BRONZE")<br>- `currentTierLevel` (Int): 현재 티어의 Solved.ac 레벨 값<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br><br>**TodaySolvedProblemResponse 구조:**<br>- `problemId` (String): 문제 ID<br>- `result` (String): 풀이 결과 ("SUCCESS", "FAIL", "TIME_OVER")<br>- `solvedAt` (LocalDateTime): 풀이 일시 (ISO 8601 형식)<br><br>**QuoteResponse 구조:**<br>- `id` (String): 명언 ID<br>- `content` (String): 명언 내용<br>- `author` (String): 저자명 | JWT Token |

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
  },
  "currentTierTitle": "Gold V",
  "nextTierTitle": "Gold IV",
  "currentRating": 850,
  "requiredRatingForNextTier": 950,
  "progressPercentage": 33
}
```

---

## StudentController

학생 프로필 관리 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| PATCH | `/api/v1/students/me` | 학생의 닉네임 및 비밀번호를 수정합니다. 닉네임과 비밀번호를 선택적으로 변경할 수 있으며, 비밀번호 변경 시 현재 비밀번호 검증이 필요합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`UpdateProfileRequest`<br>- `nickname` (String, optional): 변경할 닉네임<br>  - 유효성: `@Size(min=2, max=20)` (2자 이상 20자 이하)<br>  - null이면 변경하지 않음<br>- `currentPassword` (String, optional): 현재 비밀번호<br>  - 비밀번호 변경 시 필수 입력<br>- `newPassword` (String, optional): 새로운 비밀번호<br>  - 유효성: `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: AuthController의 비밀번호 정책과 동일<br>  - null이면 변경하지 않음 | `204 No Content` (성공 시) | JWT Token |
| DELETE | `/api/v1/students/me` | 로그인한 사용자의 계정 및 연관 데이터(회고/피드백)를 완전히 삭제합니다. (Hard Delete, 복구 불가) | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `204 No Content` (성공 시) | JWT Token |

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
| GET | `/api/v1/statistics` | 인증 사용자의 통계 정보를 조회합니다. 최근 365일 히트맵(회고 생성일 기준), 누적 풀이/회고/실패, 평균 풀이 시간, 성공률, 카테고리/약점 통계를 반환합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `StatisticsResponse`<br><br>**StatisticsResponse 구조:**<br>- `monthlyHeatmap` (List<HeatmapDataResponse>): 최근 365일 활동 히트맵 (회고 기준)<br>- `totalSolved` (Int): 누적 성공 문제 수(고유 문제 기준)<br>- `totalRetrospectives` (Long): 누적 회고 수<br>- `totalFailures` (Long): 실패/시간초과 회고 수<br>- `averageSolveTime` (Double): 평균 풀이 시간(초)<br>- `successRate` (Double): 성공률(%)<br>- `categoryStats` (List<CategoryStatResponse>): 성공 카테고리 통계<br>- `weaknessStats` (List<CategoryStatResponse>): 실패 카테고리 통계<br><br>**HeatmapDataResponse 구조:**<br>- `date` (String): 날짜 (ISO 8601 형식)<br>- `count` (Int): 해당 날짜 문제 수<br>- `problemIds` (List<String>): 해당 날짜 문제 ID 목록 | JWT Token |

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
      "count": 3,
      "problemIds": ["1000", "1001", "1002"]
    },
    {
      "date": "2024-01-16",
      "count": 2,
      "problemIds": ["10869", "2557"]
    }
  ],
  "totalSolved": 150,
  "totalRetrospectives": 180,
  "totalFailures": 30,
  "averageSolveTime": 842.5,
  "successRate": 83.3,
  "categoryStats": [
    { "category": "DP", "count": 42 }
  ],
  "weaknessStats": [
    { "category": "Greedy", "count": 12 }
  ]
}
```

---

## RankingController

랭킹 조회 관련 API를 제공합니다. **회고(Retrospective) 작성 수** 기준으로 기간별 랭킹을 조회할 수 있습니다. (DAILY/WEEKLY/MONTHLY/TOTAL)

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/ranks` | 기간별 회고 작성 수 기준 상위 랭킹을 조회합니다. 동점자는 같은 순위로 처리합니다. | **Query Parameters:**<br>- `limit` (Int, optional, default: 100): 1~1000<br>- `period` (String, optional, default: TOTAL): DAILY/WEEKLY/MONTHLY/TOTAL | `List<LeaderboardResponse>`<br><br>**LeaderboardResponse 구조:**<br>- `rank` (Int): 순위 (1부터 시작)<br>- `studentId` (String): 학생 ID (클라이언트 내 순위 식별용)<br>- `nickname` (String): 닉네임<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값)<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `retrospectiveCount` (Long): 회고 작성 수<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br>- `profileImageUrl` (String, nullable): 프로필 이미지 URL (향후 확장용, 현재는 null) | None |

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
    "retrospectiveCount": 42,
    "consecutiveSolveDays": 30,
    "profileImageUrl": null
  },
  {
    "rank": 2,
    "nickname": "seconduser",
    "tier": "PLATINUM",
    "tierLevel": 18,
    "rating": 2000,
    "retrospectiveCount": 30,
    "consecutiveSolveDays": 15,
    "profileImageUrl": null
  },
  {
    "rank": 3,
    "nickname": "thirduser",
    "tier": "GOLD",
    "tierLevel": 13,
    "rating": 1200,
    "retrospectiveCount": 12,
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

## AdminController

관리자 전용 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/users` | 페이징을 적용하여 전체 회원 목록을 조회합니다. Rating 기준 내림차순으로 정렬됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상) | `Page<AdminUserResponse>`<br><br>**AdminUserResponse 구조:**<br>- `id` (String): 학생 ID<br>- `nickname` (String): 닉네임<br>- `bojId` (String, nullable): BOJ ID (소셜 로그인 사용자는 null)<br>- `email` (String, nullable): 이메일 (소셜 로그인 사용자만 존재)<br>- `provider` (String): 인증 제공자 (BOJ, GOOGLE, GITHUB, NAVER)<br>- `role` (String): 사용자 권한 (GUEST, USER, ADMIN)<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `currentTier` (String): 현재 티어명 (예: "GOLD")<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br><br>**Page 구조:**<br>- `content` (List<AdminUserResponse>): 회원 목록<br>- `totalElements` (Long): 전체 회원 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/users/{studentId}` | 특정 회원을 강제로 탈퇴시킵니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `studentId` (String, required): 학생 ID | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): 응답 메시지 ("회원이 성공적으로 탈퇴되었습니다.") | JWT Token (ADMIN) |
| PATCH | `/api/v1/admin/users/{studentId}` | 사용자 권한(Role), 닉네임, BOJ ID, 비밀번호를 선택적으로 수정합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AdminUserUpdateDto` (optional fields)<br>- `role` (String, optional): ROLE_USER/ROLE_ADMIN<br>- `nickname` (String, optional)<br>- `bojId` (String, optional)<br>- `password` (String, optional) | `204 No Content` (응답 본문 없음) | JWT Token (ADMIN) |

**예시 요청 (전체 회원 목록 조회):**
```http
GET /api/v1/admin/users?page=0&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (전체 회원 목록 조회):**
```json
{
  "content": [
    {
      "id": "student-123",
      "nickname": "user1",
      "bojId": "user1",
      "email": null,
      "provider": "BOJ",
      "role": "USER",
      "rating": 1500,
      "currentTier": "PLATINUM",
      "consecutiveSolveDays": 10
    },
    {
      "id": "student-456",
      "nickname": "user2",
      "bojId": null,
      "email": "user2@example.com",
      "provider": "GOOGLE",
      "role": "GUEST",
      "rating": 0,
      "currentTier": "UNRATED",
      "consecutiveSolveDays": 0
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "hasNext": false,
  "hasPrevious": false
}
```

**예시 요청 (회원 강제 탈퇴):**
```http
DELETE /api/v1/admin/users/student-123
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (회원 강제 탈퇴):**
```json
{
  "message": "회원이 성공적으로 탈퇴되었습니다."
}
```

**에러 응답 예시 (ADMIN 권한 없음):**
```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "ACCESS_DENIED",
  "message": "Access Denied"
}
```

**에러 응답 예시 (존재하지 않는 회원 탈퇴):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "STUDENT_NOT_FOUND",
  "message": "학생을 찾을 수 없습니다. studentId=non-existent"
}
```

| GET | `/api/v1/admin/quotes` | 페이징을 적용하여 명언 목록을 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상) | `Page<QuoteResponse>`<br><br>**QuoteResponse 구조:**<br>- `id` (String): 명언 ID<br>- `content` (String): 명언 내용<br>- `author` (String): 저자명<br><br>**Page 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| POST | `/api/v1/admin/quotes` | 새로운 명언을 추가합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`QuoteCreateRequest`<br>- `content` (String, required): 명언 내용<br>  - 유효성: `@NotBlank`<br>- `author` (String, required): 저자명<br>  - 유효성: `@NotBlank` | `QuoteResponse`<br><br>**QuoteResponse 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/quotes/{quoteId}` | 특정 명언을 삭제합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `quoteId` (String, required): 명언 ID | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): 응답 메시지 ("명언이 성공적으로 삭제되었습니다.") | JWT Token (ADMIN) |
| GET | `/api/v1/admin/feedbacks` | 페이징을 적용하여 피드백 목록을 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상) | `Page<FeedbackResponse>`<br><br>**FeedbackResponse 구조:**<br>- `id` (String): 피드백 ID<br>- `writerId` (String): 작성자 ID (Student ID)<br>- `content` (String): 피드백 내용<br>- `type` (String): 피드백 유형 ("BUG", "SUGGESTION")<br>- `status` (String): 처리 상태 ("PENDING", "COMPLETED")<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시<br><br>**Page 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| PATCH | `/api/v1/admin/feedbacks/{feedbackId}/status` | 피드백의 처리 상태를 변경합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `feedbackId` (String, required): 피드백 ID<br><br>**Request Body:**<br>`FeedbackStatusUpdateRequest`<br>- `status` (FeedbackStatus, required): 새로운 상태 ("PENDING", "COMPLETED") | `FeedbackResponse`<br><br>**FeedbackResponse 구조:**<br>(위와 동일) | JWT Token (ADMIN) |

**예시 요청 (명언 목록 조회):**
```http
GET /api/v1/admin/quotes?page=0&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (명언 목록 조회):**
```json
{
  "content": [
    {
      "id": "quote-123",
      "content": "코딩은 90%의 디버깅과 10%의 버그 생성으로 이루어진다.",
      "author": "Unknown"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "hasNext": false,
  "hasPrevious": false
}
```

**예시 요청 (명언 추가):**
```http
POST /api/v1/admin/quotes
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "새로운 명언 내용",
  "author": "작가명"
}
```

**예시 응답 (명언 추가):**
```json
{
  "id": "quote-456",
  "content": "새로운 명언 내용",
  "author": "작가명"
}
```

**예시 요청 (명언 삭제):**
```http
DELETE /api/v1/admin/quotes/quote-123
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (명언 삭제):**
```json
{
  "message": "명언이 성공적으로 삭제되었습니다."
}
```

**예시 요청 (피드백 목록 조회):**
```http
GET /api/v1/admin/feedbacks?page=0&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (피드백 목록 조회):**
```json
{
  "content": [
    {
      "id": "feedback-123",
      "writerId": "student-123",
      "content": "로그인 시 에러가 발생합니다.",
      "type": "BUG",
      "status": "PENDING",
      "createdAt": "2024-01-15T10:30:00",
      "updatedAt": "2024-01-15T10:30:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 20,
  "hasNext": false,
  "hasPrevious": false
}
```

**예시 요청 (피드백 상태 변경):**
```http
PATCH /api/v1/admin/feedbacks/feedback-123/status
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "status": "COMPLETED"
}
```

**예시 응답 (피드백 상태 변경):**
```json
{
  "id": "feedback-123",
  "writerId": "student-123",
  "content": "로그인 시 에러가 발생합니다.",
  "type": "BUG",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T11:00:00"
}
```

---

## AdminDashboardController

관리자 대시보드 통계 관련 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/dashboard/stats` | 총 회원 수, 오늘 가입한 회원 수, 총 해결된 문제 수, 오늘 작성된 회고 수를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `AdminDashboardStatsResponse`<br><br>**AdminDashboardStatsResponse 구조:**<br>- `totalUsers` (Long): 총 회원 수<br>- `todaySignups` (Long): 오늘 가입한 회원 수<br>- `totalSolvedProblems` (Long): 총 해결된 문제 수 (SUCCESS인 Solution 개수)<br>- `todayRetrospectives` (Long): 오늘 작성된 회고 수 | JWT Token (ADMIN) |
| GET | `/api/v1/admin/dashboard/metrics` | 최근 N분 성능 메트릭(분당 요청량, 응답속도, 에러율, 상태코드 분포)을 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br>**Query:**<br>- `minutes` (optional, default: 30, max: 120) | `PerformanceMetricsResponse`<br><br>**PerformanceMetricsResponse 구조:**<br>- `rpm` (Double): 평균 분당 요청 수<br>- `averageResponseTime` (Double): 평균 응답 시간(ms)<br>- `p95ResponseTime` (Double): P95 응답 시간(ms)<br>- `maxResponseTime` (Double): 최대 응답 시간(ms)<br>- `totalRequests` (Long): 집계 기간 총 요청 수<br>- `errorRequests` (Long): 4xx/5xx 요청 수<br>- `serverErrorRequests` (Long): 5xx 요청 수<br>- `errorRate` (Double): 전체 에러율(%)<br>- `serverErrorRate` (Double): 서버 에러율(%)<br>- `slowRequestRate` (Double): 1초 이상 응답 비율(%)<br>- `timeRangeMinutes` (Int): 집계 구간(분)<br>- `statusCodeSummary` (List): 상태코드별 요청 수/비율 상위 6개<br>- `rpmTimeSeries` (List): 분당 요청 수 시계열<br>- `latencyTimeSeries` (List): 분당 평균 응답시간(ms) 시계열<br>- `errorRateTimeSeries` (List): 분당 에러율(%) 시계열 | JWT Token (ADMIN) |

**예시 요청:**
```http
GET /api/v1/admin/dashboard/stats
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답:**
```json
{
  "totalUsers": 150,
  "todaySignups": 5,
  "totalSolvedProblems": 1250,
  "todayRetrospectives": 12
}
```

**예시 요청 (성능 메트릭):**
```http
GET /api/v1/admin/dashboard/metrics?minutes=30
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (성능 메트릭):**
```json
{
  "rpm": 42.8,
  "averageResponseTime": 133.4,
  "p95ResponseTime": 520.0,
  "maxResponseTime": 1910.0,
  "totalRequests": 1284,
  "errorRequests": 12,
  "serverErrorRequests": 3,
  "errorRate": 0.93,
  "serverErrorRate": 0.23,
  "slowRequestRate": 1.56,
  "timeRangeMinutes": 30,
  "statusCodeSummary": [
    { "statusCode": 200, "count": 1203, "ratio": 93.69 },
    { "statusCode": 404, "count": 9, "ratio": 0.70 },
    { "statusCode": 500, "count": 3, "ratio": 0.23 }
  ],
  "rpmTimeSeries": [
    { "timestamp": 1739862000, "value": 39.0 },
    { "timestamp": 1739862060, "value": 44.0 }
  ],
  "latencyTimeSeries": [
    { "timestamp": 1739862000, "value": 121.5 },
    { "timestamp": 1739862060, "value": 147.3 }
  ],
  "errorRateTimeSeries": [
    { "timestamp": 1739862000, "value": 0.0 },
    { "timestamp": 1739862060, "value": 2.27 }
  ]
}
```

---

## AdminLogController

관리자 AI 리뷰 로그 조회/정리 API를 제공합니다. ADMIN 권한이 필요합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/logs` | AI 리뷰 로그 목록을 조회합니다. | **Query:** `bojId(optional)`, `page`, `size` | `Page<AdminLogResponse>` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/logs/{logId}` | 로그 상세를 조회합니다. | **Path:** `logId` | `AdminLogResponse` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/logs/cleanup/preview` | 삭제 예정 건수를 미리 조회합니다. | **Query:** `mode=OLDER_THAN_DAYS\|KEEP_RECENT_DAYS`, `olderThanDays?`, `keepDays?` | `LogCleanupPreviewResponse` | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/logs/cleanup` | 로그 정리를 실행합니다. | **Query:** `mode=OLDER_THAN_DAYS\|KEEP_RECENT_DAYS`, `olderThanDays?`, `keepDays?` | `LogCleanupResponse` | JWT Token (ADMIN) |

**LogCleanupPreviewResponse 구조**
- `mode` (String): `OLDER_THAN_DAYS` 또는 `KEEP_RECENT_DAYS`
- `referenceDays` (Int): 기준 일수
- `cutoffAt` (LocalDateTime): 삭제 기준 시각
- `deletableCount` (Long): 삭제 예정 건수
- `statusBreakdown` (Map<String, Long>): 상태별 삭제 예정 건수

**LogCleanupResponse 구조**
- `message` (String)
- `mode` (String)
- `referenceDays` (Int)
- `cutoffAt` (LocalDateTime)
- `deletedCount` (Long)

---

## ProblemCollectorController

문제 데이터 수집/재수집/운영 제어 API를 제공합니다. ADMIN 권한이 필요합니다.  
모든 상태 조회 API는 공통 `JobStatusUnifiedResponse` 스키마를 반환합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/problems/stats` | 문제 컬렉션 통계를 조회합니다. | **Headers:** `Authorization` | `ProblemStatsResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/collect-metadata` | 지정 범위 메타데이터 수집 작업을 시작합니다. | **Query:** `start`(required), `end`(required) | `message`, `jobId`, `range` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/collect-metadata/status/{jobId}` | 메타데이터 수집 작업 상태를 조회합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/collect-details` | 상세 정보 수집 작업을 시작합니다. | - | `message`, `jobId` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/collect-details/status/{jobId}` | 상세 정보 수집 작업 상태를 조회합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/refresh-details` | 상세 정보 강제 재수집 작업을 시작합니다. | **Query:** `start`(optional), `end`(optional), `start/end`는 함께 전달, `start <= end` | `message`, `jobId`, `range(optional)` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/refresh-details/status/{jobId}` | 상세 정보 재수집 작업 상태를 조회합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/update-language` | 전체 문제 언어 재판별 작업을 시작합니다. | - | `message`, `jobId` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/update-language/status/{jobId}` | 언어 업데이트 작업 상태를 조회합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/jobs` | 작업 목록을 조회합니다. | **Query:** `type`, `status`, `from`, `to`, `page`, `size` | `JobPageResponse<JobStatusUnifiedResponse>` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/jobs/{jobId}` | 작업 단건을 조회합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/jobs/{jobId}/cancel` | 작업을 취소합니다. (`PENDING/RUNNING -> CANCELLED`) | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/jobs/{jobId}/retry` | 작업을 체크포인트 기반으로 재시도합니다. | **Path:** `jobId` | `JobStatusUnifiedResponse` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/jobs/metrics` | 운영 메트릭을 조회합니다. | **Query:** `window=DAY|WEEK|MONTH` | `JobMetricsResponse` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/jobs/audit` | 작업 감사 로그(실행자/시간/범위/결과)를 조회합니다. | **Query:** `type`, `status`, `from`, `to`, `page`, `size` | `JobPageResponse<JobAuditResponse>` | JWT Token (ADMIN) |

**공통 상태 응답 (`JobStatusUnifiedResponse`)**
- `jobId` (String)
- `jobType` (String): `COLLECT_METADATA` / `COLLECT_DETAILS` / `REFRESH_DETAILS` / `UPDATE_LANGUAGE`
- `status` (String): `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`
- `queuedAt` (Long, Unix seconds)
- `startedAt` (Long, nullable, Unix seconds)
- `lastHeartbeatAt` (Long, nullable, Unix seconds)
- `completedAt` (Long, nullable, Unix seconds)
- `totalCount` (Int)
  - 보장 규칙: `PENDING`/`RUNNING` 상태에서도 0이 아닌 유효 범위 기준 총 작업 수를 반환합니다.
- `processedCount` (Int)
- `successCount` (Int)
- `failCount` (Int)
- `progressPercentage` (Int, 0~100)
- `estimatedRemainingSeconds` (Long, nullable)
- `queuePosition` (Int, nullable)
- `range` (Object, nullable): `{ "start": Int?, "end": Int? }`
- `lastCheckpointId` (String, nullable)
- `errorCode` (String, nullable)
- `errorMessage` (String, nullable)
- `createdBy` (String)

**상태 전이**
- `PENDING -> RUNNING -> COMPLETED|FAILED|CANCELLED`

**표준 에러 코드**
- `INVALID_RANGE`
- `JOB_NOT_FOUND`
- `QUEUE_TIMEOUT`
- `WORKER_UNAVAILABLE`
- `JOB_ALREADY_TERMINAL`

---

## FeedbackController

고객의 소리(피드백) 관련 API를 제공합니다. 사용자는 버그 리포트나 건의사항을 제출할 수 있습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/feedback` | 버그 리포트 또는 건의사항을 등록합니다. JWT 토큰에서 사용자 ID를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`FeedbackCreateRequest`<br>- `content` (String, required): 피드백 내용<br>  - 유효성: `@NotBlank`, 최소 10자 이상<br>- `type` (FeedbackType, required): 피드백 유형<br>  - 값: "BUG" (버그 리포트), "SUGGESTION" (건의사항) | `FeedbackResponse`<br><br>**FeedbackResponse 구조:**<br>- `id` (String): 피드백 ID<br>- `writerId` (String): 작성자 ID (Student ID)<br>- `content` (String): 피드백 내용<br>- `type` (String): 피드백 유형 ("BUG", "SUGGESTION")<br>- `status` (String): 처리 상태 ("PENDING", "COMPLETED")<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시 | JWT Token |

**예시 요청:**
```http
POST /api/v1/feedback
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "로그인 시 에러가 발생합니다. 자세한 내용은...",
  "type": "BUG"
}
```

**예시 응답:**
```json
{
  "id": "feedback-123",
  "writerId": "student-123",
  "content": "로그인 시 에러가 발생합니다. 자세한 내용은...",
  "type": "BUG",
  "status": "PENDING",
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**에러 응답 예시 (내용이 10자 미만):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "피드백 내용은 10자 이상이어야 합니다."
}
```

---

## TemplateController

회고 템플릿 관리 관련 API를 제공합니다. 사용자는 자신만의 커스텀 템플릿을 생성하고 관리할 수 있으며, 템플릿에 매크로 변수를 사용하여 문제 정보를 동적으로 치환할 수 있습니다.

> 최신 정책: 템플릿 관리/편집은 `TemplateController` 엔드포인트를 사용하고, 회고 작성 화면의 기본 템플릿 조회는 `GET /api/v1/retrospectives/template`를 사용합니다.  
> `POST /api/v1/retrospectives/template/static` 엔드포인트는 제공하지 않습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/templates` | 인증된 사용자의 커스텀 템플릿과 시스템 기본 템플릿 목록을 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `List<TemplateResponse>`<br><br>**TemplateResponse 구조:**<br>- `id` (String): 템플릿 ID<br>- `studentId` (String, nullable): 템플릿 소유자 ID (시스템 템플릿은 null)<br>- `title` (String): 템플릿 이름<br>- `content` (String): 템플릿 내용 (마크다운 원본)<br>- `type` (String): 템플릿 타입 ("SYSTEM", "CUSTOM")<br>- `isDefaultSuccess` (Boolean): 성공용 기본 템플릿 여부<br>- `isDefaultFail` (Boolean): 실패용 기본 템플릿 여부<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시 | JWT Token |
| GET | `/api/v1/templates/summaries` | 템플릿 목록 요약을 조회합니다(content 제외). 항상 시스템 템플릿 + 사용자 커스텀 템플릿을 함께 반환하며, `isDefaultSuccess`/`isDefaultFail`은 사용자 기본값이 비정상인 경우에도 시스템 fallback 기준으로 계산됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `List<TemplateSummaryResponse>`<br><br>**TemplateSummaryResponse 구조:**<br>- `id` (String): 템플릿 ID<br>- `studentId` (String, nullable): 템플릿 소유자 ID (시스템 템플릿은 null)<br>- `title` (String): 템플릿 이름<br>- `type` (String): 템플릿 타입 (`SYSTEM`, `CUSTOM`)<br>- `isDefaultSuccess` (Boolean): 성공 기본 템플릿 여부<br>- `isDefaultFail` (Boolean): 실패 기본 템플릿 여부<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시 | JWT Token |
| GET | `/api/v1/templates/presets` | 커스텀 템플릿 작성 시 활용할 수 있는 추천 섹션 목록을 조회합니다. 성공(SUCCESS), 실패(FAIL), 공통(COMMON) 카테고리별로 분류되어 제공됩니다. 프론트엔드에서 섹션 추가 시 가이드 질문(코칭 질문)을 함께 넣을지 선택할 수 있습니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `List<TemplatePresetResponse>`<br><br>**TemplatePresetResponse 구조:**<br>- `title` (String): 섹션 제목 (버튼에 표시될 이름, 예: "💡 핵심 로직")<br>- `guide` (String): 섹션 작성 가이드 (툴팁용, 예: "이 문제의 가장 중요한 점화식이나 접근법은 무엇인가요?")<br>- `category` (String): 섹션 카테고리 ("SUCCESS", "FAIL", "COMMON")<br>- `markdownContent` (String): 삽입될 마크다운 내용 (이모지 포함, 예: "## 💡 핵심 로직\n\n")<br>- `contentGuide` (String, nullable): 본문에 삽입될 가이드 질문(코칭 질문) (선택적으로 사용) | JWT Token |
| POST | `/api/v1/templates/preview` | 템플릿을 저장하지 않고 미리보기로 렌더링합니다. 매크로 변수를 실제 문제 데이터로 치환합니다. 문제 메타가 DB에 없는 경우에도 최소 문제 정보로 fallback 렌더링하여 빈 응답/500을 방지합니다. 코드 블록 내의 `{{language}}`는 프로그래밍 언어로 치환되며, `programmingLanguage`가 없으면 `code`에서 자동 감지합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`TemplatePreviewRequest`<br>- `templateContent` (String, required): 미리보기할 템플릿 내용<br>  - 유효성: `@NotBlank`<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Min(1)`<br>- `programmingLanguage` (String, optional): 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON", "CPP")<br>  - 코드 블록의 언어 태그로 사용됨<br>  - 제공되지 않으면 `code` 필드에서 자동 감지<br>- `code` (String, optional): 제출한 코드<br>  - `programmingLanguage`가 없을 때 CodeLanguageDetector로 언어 자동 감지에 사용<br>  - 가중치 기반 언어 감지 시스템 사용 | `TemplateRenderResponse`<br><br>**TemplateRenderResponse 구조:**<br>- `renderedContent` (String): 매크로가 치환된 템플릿 내용<br>- `fallbackUsed` (Boolean): fallback 적용 여부<br>- `fallbackReason` (String, nullable): fallback 사유 코드 | JWT Token |
| POST | `/api/v1/templates/{id}/render` | 저장된 템플릿을 문제 데이터와 결합해 렌더링합니다. 긴 코드 payload 전송을 위해 POST body를 기본 경로로 사용합니다. 렌더링 경로는 외부 API를 직접 호출하지 않고 DB 우선 + fallback 문제 정보로 처리하여 지연/실패를 줄입니다. 요청한 `id` 템플릿이 없으면 사용자 기본 템플릿(성공/실패 규칙 기준)으로 fallback 하며, 타인 커스텀 템플릿 접근은 `ACCESS_DENIED`를 반환합니다. 렌더링은 `app.template.render-timeout-millis`(기본 4000ms) 제한을 가지며, 타임아웃 시 `app.template.timeout-fallback-enabled=true`이면 기본 템플릿 fallback 본문을 `200`으로 반환하고(`fallbackUsed=true`), 비활성화 시 `TEMPLATE_RENDER_TIMEOUT(504)`를 반환합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Request Body:**<br>`TemplateRenderRequest`<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Min(1)`<br>- `programmingLanguage` (String, optional): 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON", "CPP")<br>  - 코드 블록의 언어 태그로 사용됨<br>  - 제공되지 않으면 `code` 필드에서 자동 감지<br>- `code` (String, optional): 제출한 코드<br>  - `programmingLanguage`가 없을 때 CodeLanguageDetector로 언어 자동 감지에 사용<br>  - 가중치 기반 언어 감지 시스템 사용 | `TemplateRenderResponse`<br><br>**TemplateRenderResponse 구조:**<br>- `renderedContent` (String): 매크로가 치환된 템플릿 내용<br>- `fallbackUsed` (Boolean): fallback 적용 여부<br>- `fallbackReason` (String, nullable): fallback 사유 코드 (`TEMPLATE_RENDER_TIMEOUT`) | JWT Token |
| GET | `/api/v1/templates/{id}/render` | (레거시 호환) 저장된 템플릿을 문제 데이터와 결합하여 렌더링된 템플릿을 반환합니다. 신규 클라이언트는 POST 사용을 권장합니다. POST와 동일하게 템플릿 fallback/timeout 정책을 적용합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Query Parameters:**<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Min(1)`<br>- `programmingLanguage` (String, optional)<br>- `code` (String, optional) | `TemplateRenderResponse` | JWT Token |
| POST | `/api/v1/templates` | 새로운 커스텀 템플릿을 생성합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`TemplateRequest`<br>- `title` (String, required): 템플릿 이름<br>  - 유효성: `@NotBlank`, 최대 100자<br>- `content` (String, required): 템플릿 내용 (마크다운, 매크로 포함)<br>  - 유효성: `@NotBlank`, 최대 10000자 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
| PUT | `/api/v1/templates/{id}` | 커스텀 템플릿을 수정합니다. 시스템 템플릿은 수정할 수 없습니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Request Body:**<br>`TemplateRequest`<br>- `title` (String, required): 템플릿 이름<br>  - 유효성: `@NotBlank`, 최대 100자<br>- `content` (String, required): 템플릿 내용<br>  - 유효성: `@NotBlank`, 최대 10000자 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
| PUT | `/api/v1/templates/{id}/default` | 특정 템플릿을 성공 또는 실패용 기본값으로 설정합니다. 기존 기본 템플릿은 자동으로 해제됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Query Parameters:**<br>- `category` (String, required): 템플릿 카테고리 ("SUCCESS" 또는 "FAIL")<br>  - `SUCCESS`: 성공용 기본 템플릿으로 설정<br>  - `FAIL`: 실패용 기본 템플릿으로 설정 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
| GET | `/api/v1/templates/default` | 성공 또는 실패용 기본 템플릿을 조회합니다. 사용자가 설정한 기본 템플릿이 없으면 시스템 기본 템플릿을 반환합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `category` (String, required): 템플릿 카테고리 ("SUCCESS" 또는 "FAIL")<br>  - `SUCCESS`: 성공용 기본 템플릿 조회<br>  - `FAIL`: 실패용 기본 템플릿 조회 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
| DELETE | `/api/v1/templates/{id}` | 커스텀 템플릿을 삭제합니다. 시스템 템플릿은 삭제할 수 없습니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID | `204 No Content` (응답 본문 없음) | JWT Token |

**템플릿 매크로 변수:**

템플릿 내용에서 다음과 같은 매크로 변수를 사용할 수 있습니다:
- `{{problemId}}`: 문제 ID (예: "1000")
- `{{problemTitle}}`: 문제 제목 (예: "A+B")
- `{{tier}}`: 티어명 (예: "BRONZE", "GOLD")
- `{{language}}`: 문제 설명 언어를 대문자로 변환 (예: "ko" -> "KO", "en" -> "EN")
  - **주의**: 코드 블록 내의 `{{language}}`는 프로그래밍 언어로 치환됩니다 (예: ````kotlin`, ````java`)
  - 일반 텍스트의 `{{language}}`는 문제 설명 언어로 치환됩니다
- `{{link}}`: 문제 링크 (예: "https://www.acmicpc.net/problem/1000")
- `{{timeTaken}}`: 풀이 소요 시간 (예: "3분 14초", "30초", 기록 없으면 "-")
- `{{result}}`: 풀이 결과 (예: "해결", "미해결", 기록이 없으면 "해결/미해결")
- `{{site}}`: 문제 출처 사이트 이름 (예: "백준/BOJ")

**코드 블록 언어 태그 처리:**
- 코드 블록 내의 `{{language}}`는 프로그래밍 언어 코드로 치환됩니다
- 예: ````{{language}}` → ````kotlin` (programmingLanguage="KOTLIN"인 경우)
- 프로그래밍 언어가 제공되지 않으면:
  - `code` 파라미터가 있으면 CodeLanguageDetector를 사용하여 자동 감지
  - `code` 파라미터도 없으면 기본값 "text"로 치환
- CodeLanguageDetector는 가중치 기반 언어 감지 시스템을 사용하여 정확도를 향상시킵니다
- 문법이 비슷한 언어들(예: Python, Ruby, Scala)도 구분하여 감지합니다
- 지원 언어: JAVA, KOTLIN, PYTHON, CPP, CSHARP, GO, JAVASCRIPT, R, RUBY, SCALA, SWIFT, C, TEXT

**예시 요청 (템플릿 목록 조회):**
```http
GET /api/v1/templates
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (템플릿 요약 목록 조회):**
```http
GET /api/v1/templates/summaries
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (템플릿 목록 조회):**
```json
[
  {
    "id": "template-1",
    "studentId": "student-123",
    "title": "나만의 템플릿",
    "content": "# {{problemTitle}}\n\n문제 ID: {{problemId}}",
    "type": "CUSTOM",
    "isDefaultSuccess": true,
    "isDefaultFail": false,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00"
  },
  {
    "id": "template-system-1",
    "studentId": null,
    "title": "Simple(요약)",
    "content": "# {{problemTitle}}\n\n## 핵심 로직",
    "type": "SYSTEM",
    "isDefaultSuccess": true,
    "isDefaultFail": false,
    "createdAt": "2024-01-01T00:00:00",
    "updatedAt": "2024-01-01T00:00:00"
  }
]
```

**예시 요청 (템플릿 생성):**
```http
POST /api/v1/templates
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "title": "나만의 템플릿",
  "content": "# {{problemTitle}}\n\n문제 ID: {{problemId}}\n\n## 핵심 로직\n- 여기에 로직을 작성하세요."
}
```

**예시 응답 (템플릿 생성):**
```json
{
  "id": "template-1",
  "studentId": "student-123",
  "title": "나만의 템플릿",
  "content": "# {{problemTitle}}\n\n문제 ID: {{problemId}}\n\n## 핵심 로직\n- 여기에 로직을 작성하세요.",
  "type": "CUSTOM",
  "isDefaultSuccess": false,
  "isDefaultFail": false,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**예시 요청 (템플릿 기본값 설정 - 성공용):**
```http
PUT /api/v1/templates/template-1/default?category=SUCCESS
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (템플릿 기본값 설정 - 실패용):**
```http
PUT /api/v1/templates/template-1/default?category=FAIL
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (템플릿 기본값 설정):**
```json
{
  "id": "template-1",
  "studentId": "student-123",
  "title": "나만의 템플릿",
  "content": "# {{problemTitle}}\n\n문제 ID: {{problemId}}",
  "type": "CUSTOM",
  "isDefaultSuccess": true,
  "isDefaultFail": false,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**예시 요청 (기본 템플릿 조회 - 성공용):**
```http
GET /api/v1/templates/default?category=SUCCESS
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (기본 템플릿 조회 - 실패용):**
```http
GET /api/v1/templates/default?category=FAIL
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (기본 템플릿 조회 - 사용자 기본 템플릿이 있는 경우):**
```json
{
  "id": "template-1",
  "studentId": "student-123",
  "title": "나만의 템플릿",
  "content": "# {{problemTitle}}\n\n문제 ID: {{problemId}}",
  "type": "CUSTOM",
  "isDefaultSuccess": true,
  "isDefaultFail": false,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**예시 응답 (기본 템플릿 조회 - 시스템 기본 템플릿 반환):**
```json
{
  "id": "template-system-1",
  "studentId": null,
  "title": "Simple(요약)",
  "content": "# {{problemTitle}}\n\n## 핵심 로직",
  "type": "SYSTEM",
  "isDefaultSuccess": true,
  "isDefaultFail": false,
  "createdAt": "2024-01-01T00:00:00",
  "updatedAt": "2024-01-01T00:00:00"
}
```

**예시 요청 (템플릿 렌더링 - 권장 POST):**
```http
POST /api/v1/templates/template-1/render
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "problemId": 1000,
  "programmingLanguage": "KOTLIN",
  "code": "fun main() { println(\"Hello\") }"
}
```

**예시 요청 (템플릿 렌더링 - 레거시 GET):**
```http
GET /api/v1/templates/template-1/render?problemId=1000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (템플릿 렌더링):**
```json
{
  "renderedContent": "# A+B\n\n문제 ID: 1000\n\n## 핵심 로직\n- 여기에 로직을 작성하세요."
}
```

**예시 요청 (템플릿 미리보기):**
```http
POST /api/v1/templates/preview
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "templateContent": "# {{problemTitle}}\n\n문제 ID: {{problemId}}",
  "problemId": 1000
}
```

**예시 응답 (템플릿 미리보기):**
```json
{
  "renderedContent": "# A+B\n\n문제 ID: 1000"
}
```

**예시 요청 (섹션 프리셋 목록 조회):**
```http
GET /api/v1/templates/presets
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (섹션 프리셋 목록 조회):**
```json
[
  {
    "title": "💡 핵심 로직",
    "guide": "이 문제의 가장 중요한 점화식이나 접근법은 무엇인가요?",
    "category": "SUCCESS",
    "markdownContent": "## 💡 핵심 로직\n\n",
    "contentGuide": "- 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?\n- 풀이의 핵심 공식을 적어보세요."
  },
  {
    "title": "⏱️ 복잡도 분석",
    "guide": "시간 복잡도와 공간 복잡도를 분석해보세요. (예: O(N), O(1))",
    "category": "SUCCESS",
    "markdownContent": "## ⏱️ 복잡도 분석\n\n",
    "contentGuide": "- 시간 복잡도: O(?)\n- 공간 복잡도: O(?)\n- 각 단계별 연산 횟수를 분석해보세요."
  },
  {
    "title": "🛠️ 사용한 자료구조",
    "guide": "왜 HashMap 대신 TreeMap을 썼는지 등 자료구조 선택 이유를 설명하세요.",
    "category": "SUCCESS",
    "markdownContent": "## 🛠️ 사용한 자료구조\n\n",
    "contentGuide": "- 어떤 자료구조를 선택했고, 왜 그 자료구조가 적합한가요?\n- 다른 자료구조를 사용했다면 어떻게 달라졌을까요?"
  },
  {
    "title": "🆚 다른 풀이 비교",
    "guide": "현재 풀이와 다른 접근 방법(DFS vs BFS 등)을 비교해보세요.",
    "category": "SUCCESS",
    "markdownContent": "## 🆚 다른 풀이 비교\n\n",
    "contentGuide": "- 다른 접근 방법은 무엇이 있나요? (DFS vs BFS, 그리디 vs DP 등)\n- 각 방법의 장단점은 무엇인가요?"
  },
  {
    "title": "✨ 리팩토링 포인트",
    "guide": "더 깔끔하게 작성할 수 있었던 변수명이나 함수 분리 포인트를 적어보세요.",
    "category": "SUCCESS",
    "markdownContent": "## ✨ 리팩토링 포인트\n\n",
    "contentGuide": "- 개선할 수 있는 변수명이나 함수명은 무엇인가요?\n- 코드 중복을 줄이기 위한 리팩토링 포인트는 무엇인가요?"
  },
  {
    "title": "🧐 실패 원인",
    "guide": "문제를 풀지 못한 주요 원인은 무엇인가요? (논리 오류, 구현 실수, 지식 부족 등)",
    "category": "FAIL",
    "markdownContent": "## 🧐 실패 원인\n\n",
    "contentGuide": "- 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과 등)\n- 로직의 어느 부분이 잘못되었나요?"
  },
  {
    "title": "🧪 반례",
    "guide": "내 코드가 틀리는 결정적인 입력값을 찾아보세요.",
    "category": "FAIL",
    "markdownContent": "## 🧪 반례\n\n",
    "contentGuide": "- 내 코드를 깨뜨리는 입력값은 무엇인가요?\n- 왜 그 입력값에서 문제가 발생했나요?"
  },
  {
    "title": "🐛 디버깅 로그",
    "guide": "어떤 입력값에서 문제가 발생했는지, 어떻게 추적했는지 기록하세요.",
    "category": "FAIL",
    "markdownContent": "## 🐛 디버깅 로그\n\n",
    "contentGuide": "- 어떤 입력값에서 문제가 발생했나요?\n- 디버깅 과정에서 발견한 패턴은 무엇인가요?"
  },
  {
    "title": "🔧 다음 시도 계획",
    "guide": "다시 풀 때 꼭 체크할 리스트를 작성하세요.",
    "category": "FAIL",
    "markdownContent": "## 🔧 다음 시도 계획\n\n",
    "contentGuide": "- 다음에 다시 풀 때 바꿀 점은 무엇인가요?\n- 체크해야 할 엣지 케이스는 무엇인가요?"
  },
  {
    "title": "🔗 참고 자료",
    "guide": "도움받은 블로그 링크나 공식 문서를 정리하세요.",
    "category": "COMMON",
    "markdownContent": "## 🔗 참고 자료\n\n",
    "contentGuide": "- 참고한 블로그 링크나 공식 문서를 기록하세요."
  },
  {
    "title": "💬 오늘의 한마디",
    "guide": "이 문제를 풀며 느낀 점을 자유롭게 적어보세요.",
    "category": "COMMON",
    "markdownContent": "## 💬 오늘의 한마디\n\n",
    "contentGuide": null
  }
]
```

**에러 응답 예시 (템플릿을 찾을 수 없음):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "TEMPLATE_NOT_FOUND",
  "message": "템플릿을 찾을 수 없습니다. id=non-existent"
}
```

**에러 응답 예시 (시스템 템플릿 수정/삭제 시도):**
```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "TEMPLATE_CANNOT_DELETE_SYSTEM",
  "message": "시스템 템플릿은 삭제할 수 없습니다."
}
```

**에러 응답 예시 (템플릿 소유자가 아님):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "템플릿 소유자가 아닙니다. studentId=student-123"
}
```

---

## MemberController

회원 닉네임/온보딩 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/members/check-nickname` | 닉네임 유효성 및 중복 여부를 확인합니다. | **Query Parameters:**<br>- `nickname` (String, required) | `Boolean` (true: 사용 가능, false: 사용 불가) | None |
| PATCH | `/api/v1/members/me/nickname` | 로그인한 사용자의 닉네임을 변경합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required)<br>**Request Body:**<br>`UpdateMyNicknameRequest`<br>- `nickname` (String, required) | `204 No Content` | JWT Token |
| PATCH | `/api/v1/members/onboarding/complete` | 온보딩 완료 상태를 true로 설정합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required) | `204 No Content` | JWT Token |
| PATCH | `/api/v1/members/onboarding/reset` | 온보딩 완료 상태를 false로 리셋합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required) | `204 No Content` | JWT Token |

---

## NoticeController

공지사항 조회/수정/삭제 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/notices` | 공지사항 목록을 페이지 단위로 조회합니다. | **Query Parameters:**<br>- `page` (Int, optional, default: 1)<br>- `size` (Int, optional, default: 10) | `Page<NoticeResponse>` | None |
| GET | `/api/v1/notices/{noticeId}` | 공지사항 상세를 조회합니다. | **Path Variables:**<br>- `noticeId` (String, required) | `NoticeResponse` | None |
| PATCH | `/api/v1/notices/{noticeId}` | 공지사항을 수정합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required, ADMIN)<br>**Path Variables:**<br>- `noticeId` (String, required)<br>**Request Body:**<br>`NoticeUpdateRequest` | `NoticeResponse` | JWT Token (ADMIN) |
| DELETE | `/api/v1/notices/{noticeId}` | 공지사항을 삭제합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required, ADMIN)<br>**Path Variables:**<br>- `noticeId` (String, required) | `204 No Content` | JWT Token (ADMIN) |

---

## PublicSystemController

공개 시스템 상태 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/system/status` | 유지보수 모드 상태를 조회합니다. | 없음 | `SystemStatusResponse`<br>- `underMaintenance` (Boolean)<br>- `maintenanceMessage` (String, nullable)<br>- `startTime` (String, nullable)<br>- `endTime` (String, nullable)<br>- `noticeId` (String, nullable) | None |

---

## AdminAuditController

관리자 감사 로그 조회 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/audit-logs` | 관리자 감사 로그를 조회합니다. 관리자/작업유형/기간 필터를 조합할 수 있습니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required, ADMIN)<br>**Query Parameters:**<br>- `page` (Int, optional, default: 1)<br>- `size` (Int, optional, default: 20)<br>- `adminId` (String, optional)<br>- `action` (AdminActionType, optional)<br>- `startDate` (LocalDateTime, optional)<br>- `endDate` (LocalDateTime, optional)<br>※ `startDate`/`endDate`는 함께 전달해야 함 | `Page<AdminAuditLogResponse>` | JWT Token (ADMIN) |

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
- 대부분의 API는 JWT 토큰 인증이 필요합니다.
- `/api/v1/admin/**` 경로는 ADMIN 권한이 필요합니다.

**JWT 토큰 사용 방법:**
1. `/api/v1/auth/signup`, `/api/v1/auth/login`, 또는 `/api/v1/auth/super-admin`을 통해 토큰을 발급받습니다.
2. 인증이 필요한 API 요청 시 `Authorization` 헤더에 토큰을 포함합니다:
   ```
   Authorization: Bearer {token}
   ```
3. 토큰은 기본적으로 30분 동안 유효합니다 (설정 가능).

**토큰 구조:**
- JWT 토큰의 `subject` (sub) 클레임에는 사용자 ID (BOJ ID 또는 Student ID)가 저장됩니다.
- Access Token은 `type=access`와 `role=USER|ADMIN`을 포함하며 보호 API 인증에 사용됩니다.
- Refresh Token은 `type=refresh`와 고유한 `jti`를 포함하며 `/api/v1/auth/refresh`에서만 사용됩니다.
- type이 없거나 role이 없거나 허용되지 않은 role을 가진 토큰은 보호 API 인증에 사용할 수 없습니다.
- 토큰은 HMAC SHA-256 알고리즘으로 서명됩니다.

**권한 기반 접근 제어:**
- 일반 사용자 (USER): 대부분의 API 접근 가능
- 관리자 (ADMIN): 모든 API 접근 가능 + `/api/v1/admin/**` 전용 API 접근 가능
- 게스트 (GUEST): 인증용 Access Token을 발급하지 않음

### 유지보수 모드 (Maintenance Mode)
서버 점검 중에도 관리자가 로그인하여 유지보수 모드를 해제할 수 있도록 설계되었습니다.

**유지보수 모드 활성화 시:**
- 일반 사용자의 대부분의 API 요청이 `503 Service Unavailable`로 차단됩니다.
- 다음 API는 유지보수 모드에서도 접근 가능합니다:
  - `GET /api/v1/notices`: 공지사항 조회 (점검 공지 확인용)
  - `GET /api/v1/system/status`: 시스템 상태 조회
  - `POST /api/v1/auth/login`: 로그인 (관리자 로그인을 위해 필수)
  - `POST /api/v1/auth/super-admin`: 슈퍼 관리자 생성
- ADMIN 권한을 가진 사용자는 유지보수 모드에서도 모든 API에 접근 가능합니다.

**유지보수 모드 에러 응답:**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "MAINTENANCE_MODE",
  "message": "서비스가 일시적으로 점검 중입니다. 잠시 후 다시 시도해주세요."
}
```

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
- `retryable` (Boolean, optional): 재시도 가능 여부 (`true`면 FE에서 즉시 재시도 버튼/대체 UI 제공 가능)

**주요 에러 코드:**
- `COMMON_INVALID_INPUT` (400): 입력값이 올바르지 않음
- `COMMON_VALIDATION_FAILED` (400): 유효성 검사 실패 (DTO 검증 실패, 쿼리 파라미터 검증 실패 등)
- `INVALID_PASSWORD` (400): 비밀번호 정책 위반 (복잡도 검증 실패)
- `UNAUTHORIZED` (401): 인증 필요
- `ACCESS_DENIED` (403): 권한 부족
- `DUPLICATE_BOJ_ID` (409): 이미 가입된 BOJ ID
- `COMMON_RESOURCE_NOT_FOUND` (404): 요청한 자원을 찾을 수 없음
- `STUDENT_NOT_FOUND` (404): 학생을 찾을 수 없음
- `PROBLEM_NOT_FOUND` (404): 문제를 찾을 수 없음
- `RETROSPECTIVE_NOT_FOUND` (404): 회고를 찾을 수 없음
- `QUOTE_NOT_FOUND` (404): 명언을 찾을 수 없음
- `FEEDBACK_NOT_FOUND` (404): 피드백을 찾을 수 없음
- `TEMPLATE_NOT_FOUND` (404): 템플릿을 찾을 수 없음
- `TEMPLATE_RENDER_TIMEOUT` (504): 템플릿 렌더링 시간 초과 (`retryable=true`)
- `TEMPLATE_CANNOT_DELETE_SYSTEM` (403): 시스템 템플릿은 삭제할 수 없음
- `MAINTENANCE_MODE` (503): 서비스가 일시적으로 점검 중
- `COMMON_INTERNAL_ERROR` (500): 서버 내부 오류

**예시 에러 응답:**

유효성 검사 실패 - DTO 검증 (400):
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "content: 회고 내용은 10자 이상이어야 합니다."
}
```

유효성 검사 실패 - 쿼리 파라미터 검증 (400):
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "getAllUsers.page: 페이지 번호는 1 이상이어야 합니다."
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

명언 없음 (404):
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "QUOTE_NOT_FOUND",
  "message": "명언을 찾을 수 없습니다."
}
```

피드백 없음 (404):
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "FEEDBACK_NOT_FOUND",
  "message": "피드백을 찾을 수 없습니다."
}
```

### 유효성 검사 실패 시
- `@NotBlank`, `@NotNull` 위반: 400 Bad Request (`COMMON_VALIDATION_FAILED`)
- `@Size`, `@Positive`, `@Min` 위반: 400 Bad Request (`COMMON_VALIDATION_FAILED`)
  - 쿼리 파라미터 검증 실패 시 `ConstraintViolationException` 발생
  - 예: `page` 파라미터가 1 미만인 경우 "페이지 번호는 1 이상이어야 합니다." 메시지 반환
- 존재하지 않는 리소스 조회: 404 Not Found (해당 리소스에 맞는 에러 코드, 예: `STUDENT_NOT_FOUND`, `PROBLEM_NOT_FOUND`, `RETROSPECTIVE_NOT_FOUND`, `QUOTE_NOT_FOUND`, `FEEDBACK_NOT_FOUND`, `TEMPLATE_NOT_FOUND`)

### 날짜/시간 형식
모든 날짜/시간 필드는 ISO 8601 형식을 따릅니다:
- 예: `2024-01-15T10:30:00`

---

## LogController

코드 로그 생성, AI 리뷰 요청/조회, AI 피드백, 사용자 AI 사용량 조회 기능을 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/logs` | 코딩 로그를 생성합니다. | **Request Body:** `LogCreateRequest`<br>- `title` (String, required)<br>- `content` (String, required, max 5000)<br>- `code` (String, required, max 5000)<br>- `isSuccess` (Boolean, optional) | `LogResponse`<br>- `id` (String) | JWT(Optional) |
| GET | `/api/v1/logs/{logId}/template` | 로그 본문 템플릿을 조회합니다. 본인이 작성한 로그만 조회할 수 있습니다. | **Path Variable:** `logId` (String, required) | `LogTemplateResponse`<br>- `template` (String) | JWT |
| POST | `/api/v1/logs/{logId}/ai-review` | AI 한 줄 리뷰를 요청합니다. 캐시가 있으면 즉시 `200`, 캐시가 없으면 비동기 작업을 등록하고 `202`를 반환합니다. 동일 API를 다시 호출하면 완료 후 캐시 결과를 받을 수 있습니다. | **Path Variable:** `logId` (String, required) | `AiReviewResponse`<br>- `review` (String)<br>- `cached` (Boolean)<br>- `inProgress` (Boolean) | JWT |
| POST | `/api/v1/logs/{logId}/feedback` | 본인이 작성한 로그의 AI 리뷰 피드백(LIKE/DISLIKE)을 저장합니다. | **Path Variable:** `logId` (String, required)<br>**Request Body:** `LogFeedbackRequest`<br>- `status` (LIKE/DISLIKE, required)<br>- `reason` (String, optional) | `{ \"message\": \"피드백이 제출되었습니다.\" }` | JWT |
| GET | `/api/v1/logs/ai-usage/me` | 내 AI 일일 사용량/잔여량/서비스 활성화 여부를 조회합니다. | 없음 | `AiUsageResponse`<br>- `limit` (Int)<br>- `usage` (Int)<br>- `remaining` (Int)<br>- `isServiceEnabled` (Boolean) | JWT |

### AI 리뷰 응답 규칙

- `200 OK`: 이미 생성된 리뷰를 반환 (`cached=true`, `inProgress=false`)
- `202 Accepted`: 리뷰 생성 작업이 접수되어 진행 중 (`cached=false`, `inProgress=true`)
- `429 Too Many Requests`: 사용자 일일 제한 초과(`AI_USER_LIMIT_EXCEEDED`) 또는 일시적 혼잡(`AI_SERVICE_BUSY`)
- `503 Service Unavailable`: 전역 제한 초과(`AI_GLOBAL_LIMIT_EXCEEDED`) 또는 서비스 비활성화(`AI_SERVICE_DISABLED`)
- `403 Forbidden`: 본인 로그가 아닌 경우(`ACCESS_DENIED`)

### 운영 기본값 (환경 변수로 변경 가능)

- 사용자 일일 제한: 5회 (`AI_CONFIG:LIMIT:USER`)
- 전역 일일 제한: 1000회 (`AI_CONFIG:LIMIT:GLOBAL`)
- 로그 `bojId`가 없는 경우 기본적으로 AI 리뷰 요청 차단 (`AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW=true`)
- 동일 코드 재요청은 코드 해시 캐시를 통해 AI 재호출 없이 즉시 반환 (TTL 7일)
- AI 호출 타임아웃: connect/read/response 10초 기본값
- AI 비동기 워커: core 2, max 4, queue 200

### 관리자 정책 제어 API

- `POST /api/v1/admin/system/ai-limits`
  - Request Body: `{"globalLimit": number, "userLimit": number}`
  - 유효성: `globalLimit`/`userLimit` 모두 `1~1000`, 그리고 `userLimit <= globalLimit`
  - 설명: 사용자/전역 일일 제한을 즉시 변경합니다.
- `POST /api/v1/admin/system/ai-review-policy`
  - Request Body: `{"requireBojForAiReview": true|false}`
  - 설명: `true`면 BOJ 연동이 없는 로그의 AI 리뷰 요청을 차단합니다. (기본값 `true`)

---

## 구현 동기화 보완 (2026-02-20)

아래 엔드포인트는 현재 서버 구현에는 존재하지만, 기존 섹션 표에 누락되어 있어 동기화 대상으로 명시합니다.

| Method | URI | 기능 요약 |
|--------|-----|-----------|
| GET | `/api/v1/system/status` | 공개 시스템 상태 조회 |
| GET | `/api/v1/auth/check-duplicate` | BOJ ID 중복 확인 |
| GET | `/api/v1/members/check-nickname` | 닉네임 중복 확인 |
| PATCH | `/api/v1/members/me/nickname` | 내 닉네임 수정 |
| PATCH | `/api/v1/members/onboarding/complete` | 온보딩 완료 처리 |
| PATCH | `/api/v1/members/onboarding/reset` | 온보딩 상태 초기화 |
| GET | `/api/v1/notices` | 공지사항 목록 조회 |
| GET | `/api/v1/notices/{noticeId}` | 공지사항 상세 조회 |
| PATCH | `/api/v1/notices/{noticeId}` | 공지사항 수정 |
| DELETE | `/api/v1/notices/{noticeId}` | 공지사항 삭제 |
| POST | `/api/v1/admin/notices` | 공지사항 생성 (관리자) |
| GET | `/api/v1/problems/search` | 문제 ID 검색 조회 |
| GET | `/api/v1/statistics/heatmap` | 날짜별 학습 히트맵 조회 |
| PATCH | `/api/v1/retrospectives/{retrospectiveId}` | 회고 수정 |
| GET | `/api/v1/admin/audit-logs` | 관리자 감사 로그 조회 |
| GET | `/api/v1/admin/dashboard/chart` | 관리자 대시보드 차트 데이터 |
| GET | `/api/v1/admin/dashboard/ai-quality` | AI 품질 통계 |
| GET | `/api/v1/admin/system/ai-status` | AI 서비스 상태 조회 |
| POST | `/api/v1/admin/system/ai-status` | AI 서비스 on/off |
| POST | `/api/v1/admin/system/ai-limits` | AI 사용량 제한 변경 |
| POST | `/api/v1/admin/system/ai-review-policy` | AI 리뷰 정책 변경 |
| GET | `/api/v1/admin/system/storage` | 저장소 통계 조회 |
| DELETE | `/api/v1/admin/system/storage/cleanup` | 저장소 정리 실행 |
| POST | `/api/v1/admin/system/maintenance` | 점검모드 전환 |
| DELETE | `/api/v1/admin/feedbacks/{feedbackId}` | 피드백 삭제 |
| POST | `/api/v1/students/sync` | 학생 정보 동기화 |
또한 기존 문서에 있던 `POST /api/v1/ai/analyze`는 현재 구현에 존재하지 않으므로 본 문서에서 미제공으로 정정했습니다.

---

## 참고사항

### Tier Enum 값
티어는 Solved.ac의 Rating(점수)을 기반으로 자동 계산됩니다.

- `UNRATED`: 0점 (Unrated)
- `BRONZE`: 30점 이상 (Solved.ac 레벨 1-5, 대표값: 3)
- `SILVER`: 200점 이상 (Solved.ac 레벨 6-10, 대표값: 8)
- `GOLD`: 800점 이상 (Solved.ac 레벨 11-15, 대표값: 13)
- `PLATINUM`: 1600점 이상 (Solved.ac 레벨 16-20, 대표값: 18)
- `DIAMOND`: 2200점 이상 (Solved.ac 레벨 21-25, 대표값: 23)
- `RUBY`: 2700점 이상 (Solved.ac 레벨 26-30, 대표값: 28)

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
