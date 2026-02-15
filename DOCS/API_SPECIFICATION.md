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
- [StudentController](#studentcontroller)
- [QuoteController](#quotecontroller)
- [StatisticsController](#statisticscontroller)
- [RankingController](#rankingcontroller)
- [AdminController](#admincontroller)
- [AdminDashboardController](#admindashboardcontroller)
- [ProblemCollectorController](#problemcollectorcontroller)
- [FeedbackController](#feedbackcontroller)
- [TemplateController](#templatecontroller)
- [Swagger 카테고리 통합 기준](#swagger-카테고리-통합-기준)

---

## Swagger 카테고리 통합 기준

Swagger UI에서 카테고리를 기능군 기준으로 통합합니다.

- `Auth`: 인증/인가
- `Template`: 회고 템플릿 관리/렌더링
- `Retrospective`: 회고 CRUD/검색
- `Problem`, `Study`, `Statistics`, `Ranking`, `Dashboard`: 학습/분석 기능
- `Admin`: 관리자 기능 전체(회원/로그/문제수집/시스템제어/감사로그 포함)
- `System`: 공개 시스템 상태 조회
- `Member`, `Student`, `Notice`, `Feedback`, `Quote`: 도메인별 기능

---

## AuthController

인증 관련 API를 제공합니다. Solved.ac 연동 기반의 회원가입 및 JWT 토큰 기반 로그인을 지원합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/auth/signup` | BOJ ID와 비밀번호를 입력받아 Solved.ac API로 검증 후 회원가입을 진행하고 JWT 토큰을 발급합니다. 비밀번호는 BCrypt로 암호화되어 저장됩니다. Solved.ac의 Rating(점수)을 기반으로 티어를 자동 계산합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - **비밀번호 정책:**<br>    - 영문, 숫자, 특수문자 중 **3종류 이상 조합**: 최소 **8자리** 이상<br>    - 영문, 숫자, 특수문자 중 **2종류 이상 조합**: 최소 **10자리** 이상<br>    - 공백 포함 불가 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/login` | BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생합니다. 로그인 시 Solved.ac API를 통해 Rating 및 Tier 정보를 동기화합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상) | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token<br>- `message` (String): 응답 메시지 ("로그인에 성공했습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/super-admin` | 관리자 키(adminKey)를 입력받아 검증 후 ADMIN 권한으로 계정을 생성하고 JWT 토큰을 발급합니다. 이 API는 초기 관리자 생성을 위해 permitAll로 열려있습니다. | **Request Body:**<br>`SuperAdminRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: signup API와 동일<br>- `adminKey` (String, required): 관리자 생성용 보안 키<br>  - 유효성: `@NotBlank`<br>  - 환경변수 `ADMIN_SECRET_KEY`와 일치해야 함 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (ADMIN role 포함)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/signup/finalize` | 소셜 로그인 후 약관 동의 및 닉네임 설정을 완료합니다. 신규 유저의 경우 Student 엔티티를 생성하고, 약관 동의가 완료되면 GUEST에서 USER로 역할이 변경되며 정식 Access Token이 발급됩니다. | **Request Body:**<br>`SignupFinalizeRequest`<br>- `email` (String, required): 사용자 이메일<br>  - 유효성: `@NotBlank` (null/공백 불가)<br>  - **GitHub 비공개 이메일 등 제공자에서 이메일을 내려주지 않는 경우**: 프론트엔드에서 사용자가 직접 입력한 값을 전달해야 함<br>- `provider` (String, required): 소셜 로그인 제공자 (GOOGLE, GITHUB, NAVER)<br>  - 유효성: `@NotBlank`<br>- `providerId` (String, required): 제공자별 사용자 ID<br>  - 유효성: `@NotBlank`<br>- `nickname` (String, required): 설정할 닉네임<br>  - 유효성: `@NotBlank`<br>- `bojId` (String, optional): BOJ ID (선택)<br>  - 제공된 경우 Solved.ac API로 검증 및 Rating 조회<br>  - **중복 불가** (이미 존재하는 BOJ ID면 409 발생)<br>- `isAgreedToTerms` (Boolean, required): 약관 동의 여부<br>  - 유효성: `@NotNull`<br>  - 반드시 `true`여야 함 (약관 동의는 필수)<br><br>※ 서버는 호환성을 위해 `termsAgreed`도 함께 지원합니다. | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (USER role 포함)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수, BOJ ID가 제공된 경우)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER", "BRONZE")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/find-account` | 이메일을 입력받아 가입된 소셜 제공자(Provider)를 반환합니다. | **Request Body:**<br>`FindAccountRequest`<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email` | `FindAccountResponse`<br>- `provider` (String)<br>- `message` (String) | None |
| POST | `/api/v1/auth/boj/code` | BOJ 프로필 상태 메시지 인증에 사용할 코드를 발급합니다. | 없음 | `BojCodeIssueResponse`<br>- `sessionId` (String)<br>- `code` (String)<br>- `expiresInSeconds` (Long) | None |
| POST | `/api/v1/auth/boj/verify` | BOJ 프로필 상태 메시지에서 발급 코드 포함 여부를 확인하고 성공 시 소유권 인증을 완료합니다. | **Request Body:**<br>`BojVerifyRequest`<br>- `sessionId` (String, required)<br>- `bojId` (String, required) | `BojVerifyResponse`<br>- `verified` (Boolean) | None |

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

**예시 요청 (회원가입 마무리 - 신규 유저):**
```http
POST /api/v1/auth/signup/finalize
Content-Type: application/json

{
  "email": "user@example.com",
  "provider": "GOOGLE",
  "providerId": "123456789",
  "nickname": "newuser",
  "bojId": null,
  "isAgreedToTerms": true
}
```

**예시 요청 (회원가입 마무리 - BOJ ID 포함):**
```http
POST /api/v1/auth/signup/finalize
Content-Type: application/json

{
  "email": "user@example.com",
  "provider": "GOOGLE",
  "providerId": "123456789",
  "nickname": "newuser",
  "bojId": "user123",
  "isAgreedToTerms": true
}
```

**예시 응답 (회원가입 마무리):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzdHVkZW50LTEyMyIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNjE2MjM5MDIyLCJleHAiOjE2MTYzMjU0MjJ9.signature",
  "message": "회원가입이 완료되었습니다.",
  "rating": 0,
  "tier": "BRONZE",
  "tierLevel": 3
}
```

**예시 응답 (BOJ ID 포함 시):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzdHVkZW50LTEyMyIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNjE2MjM5MDIyLCJleHAiOjE2MTYzMjU0MjJ9.signature",
  "message": "회원가입이 완료되었습니다.",
  "rating": 1223,
  "tier": "GOLD",
  "tierLevel": 13
}
```

**에러 응답 예시 (약관 동의 미완료):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "약관 동의는 필수입니다."
}
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

### 회원가입 마무리 플로우

소셜 로그인 신규 유저의 경우, OAuth 인증 후 다음 단계를 거칩니다:

1. **OAuth 인증 완료**: 신규 유저는 DB에 저장되지 않고, 쿼리 파라미터로 정보가 전달됨
2. **회원가입 마무리**: 프론트엔드에서 `/api/v1/auth/signup/finalize` API를 호출하여 약관 동의 및 닉네임 설정
3. **Student 엔티티 생성**: `finalizeSignup` API 호출 시 Student 엔티티가 생성되고 USER 권한 부여
4. **JWT 토큰 발급**: 정식 Access Token이 발급되어 로그인 완료

**기존 유저의 경우**: OAuth 인증 완료 시 즉시 JWT 토큰이 발급되어 로그인 완료

### 사용자 정보

소셜 로그인으로 가입한 사용자는 다음 정보를 가집니다:
- `provider`: 인증 제공자 (GOOGLE, GITHUB, NAVER)
- `email`: 소셜 계정 이메일 (공급자별로 다를 수 있음)
- `bojId`: null 또는 BOJ ID (회원가입 마무리 시 선택적으로 연동 가능)
- `role`: USER (약관 동의 완료 후 USER 권한 부여)

### 주의사항

- **신규 유저**: OAuth 인증 후 DB에 저장되지 않으며, `finalizeSignup` API를 통해 약관 동의 및 닉네임 설정 완료 시 Student 엔티티가 생성됩니다.
- **기존 유저**: OAuth 인증 완료 시 즉시 JWT 토큰이 발급되어 로그인 완료됩니다.
- OAuth2 인증은 Spring Security의 기본 동작을 따르므로, 공급자별 설정은 `application.yaml`에서 관리됩니다.

---

## AiAnalysisController

AI 분석 관련 API를 제공합니다. `RETROSPECTIVE_STANDARDS.md` 기반으로 섹션별 마크다운을 생성합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/ai/analyze` | 회고 섹션별 AI 분석을 요청합니다. (섹션 템플릿 기반, 템플릿 외 출력 금지) | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`AiAnalyzeRequest`<br>- `code` (String, required)<br>- `problemId` (String, required)<br>- `sectionType` (String, required): REFACTORING/BEST_PRACTICE/DEEP_DIVE/ROOT_CAUSE/COUNTER_EXAMPLE/GUIDANCE | `AiAnalyzeResponse`<br>- `sectionType` (String)<br>- `markdown` (String) | JWT Token |

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
| POST | `/api/v1/retrospectives` | 학생이 문제 풀이 후 회고를 작성합니다. 이미 해당 문제에 대한 회고가 있으면 수정됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `problemId` (String, required): 문제 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상)<br>- `summary` (String, optional): 한 줄 요약<br>  - 유효성: `@Size(max=200)` (200자 이하)<br>  - null 허용 (선택사항)<br>- `resultType` (ProblemResult, optional): 풀이 결과 타입 (SUCCESS/FAIL/TIME_OVER)<br>  - 사용자가 직접 선택한 결과임을 명시<br>  - null 허용 (선택사항)<br>- `solvedCategory` (String, optional): 사용자가 선택한 풀이 전략(알고리즘) 태그<br>  - 유효성: `@Size(max=50)` (50자 이하)<br>  - 예: "BruteForce", "Greedy" 등<br>  - null 허용 (선택사항)<br>- `solveTime` (String, optional): 풀이 소요 시간 (예: "15m 30s")<br>  - null 허용 (선택사항) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>- `id` (String): 회고 ID<br>- `studentId` (String): 학생 ID (JWT 토큰에서 자동 추출)<br>- `problemId` (String): 문제 ID<br>- `content` (String): 회고 내용<br>- `summary` (String, nullable): 한 줄 요약<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식)<br>- `updatedAt` (LocalDateTime): 수정 일시<br>- `isBookmarked` (Boolean): 북마크 여부<br>- `mainCategory` (String, nullable): 주요 알고리즘 카테고리<br>- `solutionResult` (String, nullable): 풀이 결과 (SUCCESS/FAIL/TIME_OVER)<br>- `solvedCategory` (String, nullable): 사용자가 선택한 풀이 전략 태그<br>- `solveTime` (String, nullable): 풀이 소요 시간 | JWT Token |
| GET | `/api/v1/retrospectives` | 검색 조건에 따라 회고 목록을 조회합니다. 키워드, 카테고리, 북마크 여부로 필터링할 수 있으며, 페이징을 지원합니다. | **Query Parameters:**<br>- `keyword` (String, optional): 검색 키워드 (내용 또는 문제 ID)<br>- `category` (String, optional): 카테고리 필터 (예: "DFS", "DP")<br>- `isBookmarked` (Boolean, optional): 북마크 여부 (true인 경우만 필터링)<br>- `studentId` (String, optional): 학생 ID 필터<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 10): 페이지 크기<br>  - 유효성: `@Positive` (1 이상)<br>- `sort` (String, optional): 정렬 기준 (예: "createdAt,desc" 또는 "createdAt,asc")<br>  - 기본값: "createdAt,desc" | `RetrospectivePageResponse`<br><br>**RetrospectivePageResponse 구조:**<br>- `content` (List<RetrospectiveResponse>): 회고 목록<br>- `totalElements` (Long): 전체 회고 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | None |
| GET | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 조회합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>(위와 동일) | None |
| POST | `/api/v1/retrospectives/{retrospectiveId}/bookmark` | 회고의 북마크 상태를 토글합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `BookmarkToggleResponse`<br><br>**BookmarkToggleResponse 구조:**<br>- `isBookmarked` (Boolean): 변경된 북마크 상태 | None |
| DELETE | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 삭제합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `204 No Content` (응답 본문 없음) | None |
| GET | `/api/v1/retrospectives/template` | 문제 정보를 바탕으로 회고 작성용 마크다운 템플릿을 생성합니다. resultType(SUCCESS/FAIL)에 따라 다른 템플릿이 생성됩니다. | **Query Parameters:**<br>- `problemId` (String, required): 문제 ID<br>- `resultType` (ProblemResult, required): 풀이 결과 타입 (SUCCESS/FAIL/TIME_OVER)<br>  - SUCCESS: 성공 템플릿 (핵심 접근, 시간/공간 복잡도, 개선할 점)<br>  - FAIL/TIME_OVER: 실패 템플릿 (실패 원인, 부족했던 개념, 다음 시도 계획) | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>- `template` (String): 마크다운 형식의 템플릿 문자열 | None |

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
```

**예시 응답 (회고 삭제):**
```http
HTTP/1.1 204 No Content
```

**예시 요청 (템플릿 생성 - 성공 케이스):**
```http
GET /api/v1/retrospectives/template?problemId=1000&resultType=SUCCESS
```

**예시 응답 (템플릿 생성 - 성공 케이스):**
```json
{
  "template": "# 🏆 A+B 해결 회고\n\n## 💡 핵심 접근 (Key Idea)\n\n<!-- 여기에 문제 해결의 핵심 접근 방법을 작성하세요 -->\n\n## ⏱️ 시간/공간 복잡도\n\n<!-- 여기에 시간 복잡도와 공간 복잡도를 작성하세요 -->\n\n## ✨ 개선할 점\n\n<!-- 여기에 더 나은 풀이 방법이나 개선할 점을 작성하세요 -->\n"
}
```

**예시 요청 (템플릿 생성 - 실패 케이스):**
```http
GET /api/v1/retrospectives/template?problemId=1000&resultType=FAIL
```

**예시 응답 (템플릿 생성 - 실패 케이스):**
```json
{
  "template": "# 💥 A+B 오답 노트\n\n## 🧐 실패 원인 (Why?)\n\n<!-- 여기에 문제를 풀지 못한 원인을 작성하세요 -->\n\n## 📚 부족했던 개념\n\n<!-- 여기에 부족했던 알고리즘 개념이나 자료구조를 작성하세요 -->\n\n## 🔧 다음 시도 계획\n\n<!-- 여기에 다음에 다시 시도할 때의 계획을 작성하세요 -->\n"
}
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

## RankingController

랭킹 조회 관련 API를 제공합니다. **회고(Retrospective) 작성 수** 기준으로 기간별 랭킹을 조회할 수 있습니다. (DAILY/WEEKLY/MONTHLY/TOTAL)

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/ranks` | 기간별 회고 작성 수 기준 상위 랭킹을 조회합니다. 동점자는 같은 순위로 처리합니다. | **Query Parameters:**<br>- `limit` (Int, optional, default: 100): 1~1000<br>- `period` (String, optional, default: TOTAL): DAILY/WEEKLY/MONTHLY/TOTAL | `List<LeaderboardResponse>`<br><br>**LeaderboardResponse 구조:**<br>- `rank` (Int): 순위 (1부터 시작)<br>- `nickname` (String): 닉네임<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값)<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `retrospectiveCount` (Long): 회고 작성 수<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br>- `profileImageUrl` (String, nullable): 프로필 이미지 URL (향후 확장용, 현재는 null) | None |

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
| PATCH | `/api/v1/admin/users/{studentId}` | 사용자 권한(Role), 닉네임, BOJ ID를 선택적으로 수정합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AdminUserUpdateDto` (optional fields)<br>- `role` (String, optional): ROLE_USER/ROLE_ADMIN<br>- `nickname` (String, optional)<br>- `bojId` (String, optional) | `204 No Content` (응답 본문 없음) | JWT Token (ADMIN) |

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

---

## ProblemCollectorController

문제 데이터 수집 관련 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다. 모든 작업은 비동기로 실행되며, 작업 ID를 통해 작업 상태를 조회할 수 있습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/admin/problems/collect-metadata` | Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 비동기로 수집하여 DB에 저장합니다. (Upsert 방식) 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `start` (Int, required): 시작 문제 ID<br>  - 유효성: `@Positive` (1 이상)<br>- `end` (Int, required): 종료 문제 ID (포함)<br>  - 유효성: `@Positive` (1 이상) | `Map<String, String>`<br><br>**Response 구조:**<br>- `message` (String): 응답 메시지 ("문제 메타데이터 수집 작업이 시작되었습니다.")<br>- `jobId` (String): 작업 ID (상태 조회 시 사용)<br>- `range` (String): 작업 범위 (예: "1-100") | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/collect-metadata/status/{jobId}` | 문제 메타데이터 수집 작업의 진행 상태를 조회합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `jobId` (String, required): 작업 ID | `MetadataCollectJobStatus`<br><br>**MetadataCollectJobStatus 구조:**<br>- `jobId` (String): 작업 ID<br>- `status` (String): 작업 상태 (PENDING, RUNNING, COMPLETED, FAILED)<br>- `totalCount` (Int): 전체 문제 수<br>- `processedCount` (Int): 처리된 문제 수<br>- `successCount` (Int): 성공한 문제 수<br>- `failCount` (Int): 실패한 문제 수<br>- `startProblemId` (Int): 시작 문제 ID<br>- `endProblemId` (Int): 종료 문제 ID<br>- `startedAt` (Long): 작업 시작 시간 (Unix timestamp)<br>- `completedAt` (Long, nullable): 작업 완료 시간 (Unix timestamp)<br>- `errorMessage` (String, nullable): 에러 메시지<br>- `progressPercentage` (Int): 진행률 (0~100) | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/collect-details` | DB에서 descriptionHtml이 null인 문제들의 상세 정보를 BOJ 사이트에서 비동기로 크롤링하여 업데이트합니다. Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둡니다. 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `Map<String, String>`<br><br>**Response 구조:**<br>- `message` (String): 응답 메시지 ("문제 상세 정보 크롤링 작업이 시작되었습니다.")<br>- `jobId` (String): 작업 ID (상태 조회 시 사용) | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/collect-details/status/{jobId}` | 문제 상세 정보 수집 작업의 진행 상태를 조회합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `jobId` (String, required): 작업 ID | `DetailsCollectJobStatus`<br><br>**DetailsCollectJobStatus 구조:**<br>- `jobId` (String): 작업 ID<br>- `status` (String): 작업 상태 (PENDING, RUNNING, COMPLETED, FAILED)<br>- `totalCount` (Int): 전체 문제 수<br>- `processedCount` (Int): 처리된 문제 수<br>- `successCount` (Int): 성공한 문제 수<br>- `failCount` (Int): 실패한 문제 수<br>- `startedAt` (Long): 작업 시작 시간 (Unix timestamp)<br>- `completedAt` (Long, nullable): 작업 완료 시간 (Unix timestamp)<br>- `errorMessage` (String, nullable): 에러 메시지<br>- `progressPercentage` (Int): 진행률 (0~100) | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/update-language` | DB에서 언어 정보가 null인 문제들의 언어 정보를 비동기로 업데이트합니다. 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `Map<String, String>`<br><br>**Response 구조:**<br>- `message` (String): 응답 메시지 ("문제 언어 정보 최신화 작업이 시작되었습니다.")<br>- `jobId` (String): 작업 ID (상태 조회 시 사용) | JWT Token (ADMIN) |
| GET | `/api/v1/admin/problems/update-language/status/{jobId}` | 언어 정보 업데이트 작업의 진행 상태를 조회합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `jobId` (String, required): 작업 ID | `LanguageUpdateJobStatus`<br><br>**LanguageUpdateJobStatus 구조:**<br>- `jobId` (String): 작업 ID<br>- `status` (String): 작업 상태 (PENDING, RUNNING, COMPLETED, FAILED)<br>- `totalCount` (Int): 전체 문제 수<br>- `processedCount` (Int): 처리된 문제 수<br>- `successCount` (Int): 성공한 문제 수<br>- `failCount` (Int): 실패한 문제 수<br>- `startedAt` (Long): 작업 시작 시간 (Unix timestamp)<br>- `completedAt` (Long, nullable): 작업 완료 시간 (Unix timestamp)<br>- `errorMessage` (String, nullable): 에러 메시지<br>- `progressPercentage` (Int): 진행률 (0~100) | JWT Token (ADMIN) |

**예시 요청 (메타데이터 수집):**
```http
POST /api/v1/admin/problems/collect-metadata?start=1&end=100
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (메타데이터 수집):**
```json
{
  "message": "문제 메타데이터 수집 작업이 시작되었습니다.",
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "range": "1-100"
}
```

**예시 요청 (메타데이터 수집 작업 상태 조회):**
```http
GET /api/v1/admin/problems/collect-metadata/status/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (메타데이터 수집 작업 상태 조회):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "totalCount": 100,
  "processedCount": 50,
  "successCount": 48,
  "failCount": 2,
  "startProblemId": 1,
  "endProblemId": 100,
  "startedAt": 1704067200000,
  "completedAt": null,
  "errorMessage": null,
  "progressPercentage": 50
}
```

**예시 요청 (상세 정보 크롤링):**
```http
POST /api/v1/admin/problems/collect-details
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (상세 정보 크롤링):**
```json
{
  "message": "문제 상세 정보 크롤링 작업이 시작되었습니다.",
  "jobId": "550e8400-e29b-41d4-a716-446655440001"
}
```

**예시 요청 (언어 정보 최신화):**
```http
POST /api/v1/admin/problems/update-language
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (언어 정보 최신화):**
```json
{
  "message": "문제 언어 정보 최신화 작업이 시작되었습니다.",
  "jobId": "550e8400-e29b-41d4-a716-446655440002"
}
```

**에러 응답 예시 (작업을 찾을 수 없음):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "COMMON_RESOURCE_NOT_FOUND",
  "message": "작업을 찾을 수 없습니다. jobId=non-existent-id"
}
```

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

> 최신 정책: 프론트엔드는 아래 `TemplateController` 엔드포인트만 사용합니다.  
> `POST /api/v1/retrospectives/template/static` 같은 정적 템플릿 전용 API는 제공하지 않습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/templates` | 인증된 사용자의 커스텀 템플릿과 시스템 기본 템플릿 목록을 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `List<TemplateResponse>`<br><br>**TemplateResponse 구조:**<br>- `id` (String): 템플릿 ID<br>- `studentId` (String, nullable): 템플릿 소유자 ID (시스템 템플릿은 null)<br>- `title` (String): 템플릿 이름<br>- `content` (String): 템플릿 내용 (마크다운 원본)<br>- `type` (String): 템플릿 타입 ("SYSTEM", "CUSTOM")<br>- `isDefaultSuccess` (Boolean): 성공용 기본 템플릿 여부<br>- `isDefaultFail` (Boolean): 실패용 기본 템플릿 여부<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시 | JWT Token |
| GET | `/api/v1/templates/presets` | 커스텀 템플릿 작성 시 활용할 수 있는 추천 섹션 목록을 조회합니다. 성공(SUCCESS), 실패(FAIL), 공통(COMMON) 카테고리별로 분류되어 제공됩니다. 프론트엔드에서 섹션 추가 시 가이드 질문(코칭 질문)을 함께 넣을지 선택할 수 있습니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `List<TemplatePresetResponse>`<br><br>**TemplatePresetResponse 구조:**<br>- `title` (String): 섹션 제목 (버튼에 표시될 이름, 예: "💡 핵심 로직")<br>- `guide` (String): 섹션 작성 가이드 (툴팁용, 예: "이 문제의 가장 중요한 점화식이나 접근법은 무엇인가요?")<br>- `category` (String): 섹션 카테고리 ("SUCCESS", "FAIL", "COMMON")<br>- `markdownContent` (String): 삽입될 마크다운 내용 (이모지 포함, 예: "## 💡 핵심 로직\n\n")<br>- `contentGuide` (String, nullable): 본문에 삽입될 가이드 질문(코칭 질문) (선택적으로 사용) | JWT Token |
| POST | `/api/v1/templates/preview` | 템플릿을 저장하지 않고 미리보기로 렌더링합니다. 매크로 변수를 실제 문제 데이터로 치환하여 결과를 반환합니다. 템플릿 작성 중 매크로가 올바르게 변환되는지 확인할 수 있습니다. 코드 블록 내의 `{{language}}`는 프로그래밍 언어로 치환됩니다. `programmingLanguage`가 제공되지 않으면 `code` 필드에서 CodeLanguageDetector를 사용하여 자동 감지됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`TemplatePreviewRequest`<br>- `templateContent` (String, required): 미리보기할 템플릿 내용<br>  - 유효성: `@NotBlank`<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Min(1)`<br>- `programmingLanguage` (String, optional): 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON", "CPP")<br>  - 코드 블록의 언어 태그로 사용됨<br>  - 제공되지 않으면 `code` 필드에서 자동 감지<br>- `code` (String, optional): 제출한 코드<br>  - `programmingLanguage`가 없을 때 CodeLanguageDetector로 언어 자동 감지에 사용<br>  - 가중치 기반 언어 감지 시스템 사용 | `TemplateRenderResponse`<br><br>**TemplateRenderResponse 구조:**<br>- `renderedContent` (String): 매크로가 치환된 템플릿 내용 | JWT Token |
| GET | `/api/v1/templates/{id}/render` | 저장된 템플릿을 문제 데이터와 결합하여 렌더링된 템플릿을 반환합니다. 매크로 변수를 실제 문제 데이터로 치환합니다. 코드 블록 내의 `{{language}}`는 프로그래밍 언어로 치환됩니다. `programmingLanguage`가 제공되지 않으면 `code` 파라미터에서 CodeLanguageDetector를 사용하여 자동 감지됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Query Parameters:**<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Min(1)`<br>- `programmingLanguage` (String, optional): 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON", "CPP")<br>  - 코드 블록의 언어 태그로 사용됨<br>  - 제공되지 않으면 `code` 파라미터에서 자동 감지<br>- `code` (String, optional): 제출한 코드<br>  - `programmingLanguage`가 없을 때 CodeLanguageDetector로 언어 자동 감지에 사용<br>  - 가중치 기반 언어 감지 시스템 사용 | `TemplateRenderResponse`<br><br>**TemplateRenderResponse 구조:**<br>- `renderedContent` (String): 매크로가 치환된 템플릿 내용 | JWT Token |
| POST | `/api/v1/templates` | 새로운 커스텀 템플릿을 생성합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`TemplateRequest`<br>- `title` (String, required): 템플릿 이름<br>  - 유효성: `@NotBlank`, 최대 100자<br>- `content` (String, required): 템플릿 내용 (마크다운, 매크로 포함)<br>  - 유효성: `@NotBlank`, 최소 10자, 최대 10000자 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
| PUT | `/api/v1/templates/{id}` | 커스텀 템플릿을 수정합니다. 시스템 템플릿은 수정할 수 없습니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `id` (String, required): 템플릿 ID<br><br>**Request Body:**<br>`TemplateRequest`<br>- `title` (String, required): 템플릿 이름<br>  - 유효성: `@NotBlank`, 최대 100자<br>- `content` (String, required): 템플릿 내용<br>  - 유효성: `@NotBlank`, 최소 10자, 최대 10000자 | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>(위와 동일) | JWT Token |
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

**예시 요청 (템플릿 렌더링):**
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
- `role` 클레임에는 사용자 권한 (USER, ADMIN 등)이 저장됩니다.
- 토큰은 HMAC SHA-256 알고리즘으로 서명됩니다.

**권한 기반 접근 제어:**
- 일반 사용자 (USER): 대부분의 API 접근 가능
- 관리자 (ADMIN): 모든 API 접근 가능 + `/api/v1/admin/**` 전용 API 접근 가능
- 게스트 (GUEST): 제한된 API만 접근 가능 (소셜 로그인만 완료한 상태)

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
