# DidimLog API 명세서

이 문서는 DidimLog 프로젝트의 모든 REST API 엔드포인트를 정리한 명세서입니다.

## 목차

- [AuthController](#authcontroller)
- [OAuth2 Authentication](#oauth2-authentication)
- [ProblemController](#problemcontroller)
- [StudyController](#studycontroller)
- [RetrospectiveController](#retrospectivecontroller)
- [DashboardController](#dashboardcontroller)
- [LogController](#logcontroller)
- [MemberController](#membercontroller)
- [StudentController](#studentcontroller)
- [QuoteController](#quotecontroller)
- [StatisticsController](#statisticscontroller)
- [RankingController](#rankingcontroller)
- [AdminController](#admincontroller)
- [AdminMemberController](#adminmembercontroller)
- [AdminDashboardController](#admindashboardcontroller)
- [SystemController](#systemcontroller)
- [PublicSystemController](#publicsystemcontroller)
- [ProblemCollectorController](#problemcollectorcontroller)
- [NoticeController](#noticecontroller)
- [FeedbackController](#feedbackcontroller)

---

## AuthController

인증 관련 API를 제공합니다. Solved.ac 연동 기반의 회원가입 및 JWT 토큰 기반 로그인을 지원합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/auth/signup` | BOJ ID, 비밀번호, 이메일을 입력받아 Solved.ac API로 검증 후 회원가입을 진행하고 JWT 토큰을 발급합니다. 비밀번호는 BCrypt로 암호화되어 저장됩니다. Solved.ac의 Rating(점수)을 기반으로 티어를 자동 계산합니다. 이메일은 아이디/비밀번호 찾기에 사용됩니다. | **Request Body:**<br>`SignupRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - **비밀번호 정책:**<br>    - 영문, 숫자, 특수문자 중 **3종류 이상 조합**: 최소 **8자리** 이상<br>    - 영문, 숫자, 특수문자 중 **2종류 이상 조합**: 최소 **10자리** 이상<br>    - 공백 포함 불가<br>- `email` (String, required): 이메일 주소<br>  - 유효성: `@NotBlank`, `@Email`<br>  - **중복 불가** (이미 사용 중인 이메일이면 400 발생)<br>  - 아이디/비밀번호 찾기에 사용됨 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (30분 유효)<br>- `refreshToken` (String): JWT Refresh Token (7일 유효)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/login` | BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생합니다. 로그인 시 Solved.ac API를 통해 Rating 및 Tier 정보를 동기화합니다. | **Request Body:**<br>`LoginRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상) | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (30분 유효)<br>- `refreshToken` (String): JWT Refresh Token (7일 유효)<br>- `message` (String): 응답 메시지 ("로그인에 성공했습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| GET | `/api/v1/auth/check-duplicate` | 회원가입 2단계(인증) 전, 입력한 BOJ ID가 이미 가입된 계정인지 확인합니다. | **Query Parameters:**<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank` | `BojIdDuplicateCheckResponse`<br><br>**BojIdDuplicateCheckResponse 구조:**<br>- `isDuplicate` (Boolean): 중복 여부<br>- `message` (String): 안내 메시지 | None |
| POST | `/api/v1/auth/super-admin` | 관리자 키(adminKey)를 입력받아 검증 후 ADMIN 권한으로 계정을 생성하고 JWT 토큰을 발급합니다. 이 API는 초기 관리자 생성을 위해 permitAll로 열려있습니다. | **Request Body:**<br>`SuperAdminRequest`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank`<br>- `password` (String, required): 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: signup API와 동일<br>- `email` (String, required): 이메일 주소<br>  - 유효성: `@NotBlank`, `@Email`<br>  - **중복 불가** (이미 사용 중인 이메일이면 400 발생)<br>- `adminKey` (String, required): 관리자 생성용 보안 키<br>  - 유효성: `@NotBlank`<br>  - 환경변수 `ADMIN_SECRET_KEY`와 일치해야 함 | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (ADMIN role 포함, 30분 유효)<br>- `refreshToken` (String): JWT Refresh Token (7일 유효)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/signup/finalize` | 소셜 로그인 후 약관 동의 및 닉네임 설정을 완료합니다. 신규 유저의 경우 Student 엔티티를 생성하고, 약관 동의가 완료되면 GUEST에서 USER로 역할이 변경되며 정식 Access Token이 발급됩니다. | **Request Body:**<br>`SignupFinalizeRequest`<br>- `email` (String, required): 사용자 이메일<br>  - 유효성: `@NotBlank` (null/공백 불가)<br>  - **GitHub 비공개 이메일 등 제공자에서 이메일을 내려주지 않는 경우**: 프론트엔드에서 사용자가 직접 입력한 값을 전달해야 함<br>- `provider` (String, required): 소셜 로그인 제공자 (GOOGLE, GITHUB, NAVER)<br>  - 유효성: `@NotBlank`<br>- `providerId` (String, required): 제공자별 사용자 ID<br>  - 유효성: `@NotBlank`<br>- `nickname` (String, required): 설정할 닉네임<br>  - 유효성: `@NotBlank`<br>  - **닉네임 정책:**<br>    - 길이: 2~12<br>    - 허용: 영문/숫자/완성형 한글(가-힣)/특수문자(., _, -)<br>    - 금지: 공백/한글 자모(ㄱ-ㅎ, ㅏ-ㅣ)/기타 특수문자/예약어(admin, manager)<br>    - 정규식: `^[a-zA-Z0-9가-힣._-]{2,12}$`<br>- `bojId` (String, optional): BOJ ID (선택)<br>  - 제공된 경우 Solved.ac API로 검증 및 Rating 조회<br>  - **중복 불가** (이미 존재하는 BOJ ID면 409 발생)<br>- `isAgreedToTerms` (Boolean, required): 약관 동의 여부<br>  - 유효성: `@NotNull`<br>  - 반드시 `true`여야 함 (약관 동의는 필수)<br><br>※ 서버는 호환성을 위해 `termsAgreed`도 함께 지원합니다. | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): JWT Access Token (USER role 포함, 30분 유효)<br>- `refreshToken` (String): JWT Refresh Token (7일 유효)<br>- `message` (String): 응답 메시지 ("회원가입이 완료되었습니다.")<br>- `rating` (Int): Solved.ac Rating (점수, BOJ ID가 제공된 경우)<br>- `tier` (String): 티어명 (예: "GOLD", "SILVER", "BRONZE")<br>- `tierLevel` (Int): 티어 레벨 (Solved.ac 레벨 대표값) | None |
| POST | `/api/v1/auth/find-account` | 이메일을 입력받아 가입된 소셜 제공자(Provider)를 반환합니다. | **Request Body:**<br>`FindAccountRequest`<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email` | `FindAccountResponse`<br>- `provider` (String)<br>- `message` (String) | None |
| POST | `/api/v1/auth/find-id` | 이메일을 입력받아 해당 이메일로 가입된 계정의 BOJ ID를 이메일로 전송합니다. | **Request Body:**<br>`FindIdRequest`<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email` | `FindIdPasswordResponse`<br>- `message` (String): "이메일로 아이디가 전송되었습니다." | None |
| POST | `/api/v1/auth/find-password` | 이메일과 BOJ ID를 입력받아 일치하는 계정이 있으면 비밀번호 재설정 코드(8자리 영문+숫자 조합)를 생성하여 Redis에 저장하고 이메일로 전송합니다. 코드는 30분간 유효합니다. | **Request Body:**<br>`FindPasswordRequest`<br>- `email` (String, required): 이메일<br>  - 유효성: `@NotBlank`, `@Email`<br>- `bojId` (String, required): BOJ ID<br>  - 유효성: `@NotBlank` | `FindIdPasswordResponse`<br>- `message` (String): "이메일로 비밀번호 재설정 코드가 전송되었습니다." | None |
| POST | `/api/v1/auth/reset-password` | 비밀번호 재설정 코드와 새 비밀번호를 입력받아 비밀번호를 변경합니다. 재설정 코드는 일회성이며 사용 후 삭제됩니다. | **Request Body:**<br>`ResetPasswordRequest`<br>- `resetCode` (String, required): 재설정 코드<br>  - 유효성: `@NotBlank`<br>- `newPassword` (String, required): 새 비밀번호<br>  - 유효성: `@NotBlank`, `@Size(min = 8)` | `FindIdPasswordResponse`<br>- `message` (String): "비밀번호가 성공적으로 변경되었습니다." | None |
| POST | `/api/v1/auth/boj/code` | BOJ 프로필 상태 메시지 인증에 사용할 코드를 발급합니다. | 없음 | `BojCodeIssueResponse`<br>- `sessionId` (String)<br>- `code` (String)<br>- `expiresInSeconds` (Long) | None |
| POST | `/api/v1/auth/boj/verify` | BOJ 프로필 페이지 본문 전체 텍스트에서 발급 코드 포함 여부를 확인하고 성공 시 소유권 인증을 완료합니다. Jsoup을 사용하여 백준 프로필 페이지(`https://www.acmicpc.net/user/{bojId}`)를 직접 크롤링하여 실시간으로 확인합니다. Solved.ac API의 캐싱 지연 문제를 해결하기 위해 직접 크롤링 방식을 사용합니다. | **Request Body:**<br>`BojVerifyRequest`<br>- `sessionId` (String, required): 인증 코드 발급 시 받은 세션 ID<br>- `bojId` (String, required): BOJ ID | `BojVerifyResponse`<br>- `verified` (Boolean): 인증 성공 여부 | None |
| POST | `/api/v1/auth/refresh` | Refresh Token을 사용하여 새로운 Access Token과 Refresh Token을 발급합니다. 기존 Refresh Token은 무효화됩니다 (Token Rotation). 프론트엔드에서 Access Token이 만료되어 401 에러가 발생하면 자동으로 이 API를 호출하여 토큰을 갱신합니다. Refresh Token은 Request Body 또는 Authorization 헤더(Bearer 토큰)로 전달할 수 있으며, Body가 우선순위입니다. | **Headers (선택):**<br>- `Authorization: Bearer {refreshToken}` (optional): Refresh Token을 헤더로 전달<br>  - Body에 `refreshToken`이 없을 때만 사용<br><br>**Request Body (선택):**<br>`RefreshTokenRequest`<br>- `refreshToken` (String, optional): Refresh Token<br>  - Body 또는 Header 중 하나는 필수<br>  - 둘 다 없으면 400 Bad Request ("Refresh Token이 필요합니다.") | `AuthResponse`<br><br>**AuthResponse 구조:**<br>- `token` (String): 새로운 JWT Access Token (30분 유효)<br>- `refreshToken` (String): 새로운 JWT Refresh Token (7일 유효)<br>- `message` (String): 응답 메시지 ("로그인에 성공했습니다.")<br>- `rating` (Int): 사용자 Rating (Solved.ac 점수)<br>- `tier` (String): 사용자 티어명 (예: "GOLD", "SILVER")<br>- `tierLevel` (Int): 사용자 티어 레벨 (Solved.ac 레벨 대표값) | None |

**예시 요청 (회원가입):**
```http
POST /api/v1/auth/signup
Content-Type: application/json

{
  "bojId": "user123",
  "password": "securePassword123",
  "email": "user@example.com"
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

**예시 요청 (BOJ ID 중복 체크):**
```http
GET /api/v1/auth/check-duplicate?bojId=user123
```

**예시 응답 (BOJ ID 중복 체크 - 중복):**
```json
{
  "isDuplicate": true,
  "message": "이미 가입된 BOJ ID입니다."
}
```

**예시 응답 (BOJ ID 중복 체크 - 사용 가능):**
```json
{
  "isDuplicate": false,
  "message": "사용 가능한 BOJ ID입니다."
}
```

**에러 응답 예시 (BOJ ID 중복 체크 - 필수 파라미터 누락):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "bojId: 필수 요청 파라미터입니다."
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

## ProblemController

문제 추천 및 상세 조회 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/problems/recommend` | 학생의 현재 티어보다 한 단계 높은 난이도(UserLevel + 1 ~ +2)의 문제 중, 아직 풀지 않은 문제를 추천합니다. 카테고리를 지정하면 해당 카테고리 문제만 추천합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `count` (Int, optional, default: 1): 추천할 문제 개수<br>  - 유효성: `@Positive` (1 이상)<br>- `category` (String, optional): 문제 카테고리 필터<br>  - 예: "IMPLEMENTATION", "GRAPH", "DP" 등<br>  - 미지정 시 모든 카테고리에서 추천 | `List<ProblemResponse>`<br><br>**ProblemResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 문제 카테고리<br>- `difficulty` (String): 난이도 티어명 (예: "BRONZE", "SILVER")<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL | JWT Token |
| GET | `/api/v1/problems/{problemId}` | 문제 ID로 문제 상세 정보를 조회합니다. DB에 상세 정보(HTML 본문)가 없으면 백준 웹사이트에서 실시간으로 크롤링하여 가져온 후 DB에 저장합니다. (Read-Through 전략) | **Path Variables:**<br>- `problemId` (Long, required): 문제 ID<br>  - 유효성: `@Positive` (1 이상) | `ProblemDetailResponse`<br><br>**ProblemDetailResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 문제 카테고리<br>- `difficulty` (String): 난이도 티어명 (예: "BRONZE", "SILVER")<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL<br>- `descriptionHtml` (String, nullable): 문제 본문 HTML<br>- `inputDescriptionHtml` (String, nullable): 입력 설명 HTML<br>- `outputDescriptionHtml` (String, nullable): 출력 설명 HTML<br>- `sampleInputs` (List<String>, nullable): 샘플 입력 리스트<br>- `sampleOutputs` (List<String>, nullable): 샘플 출력 리스트<br>- `tags` (List<String>): 알고리즘 분류 태그 리스트 | None |
| GET | `/api/v1/problems/search` | 문제 번호로 문제를 검색합니다. DB에 문제가 없으면 Solved.ac API로 메타데이터를 조회하고 크롤링하여 저장한 후 반환합니다. | **Query Parameters:**<br>- `q` (Long, required): 문제 번호<br>  - 유효성: `@Positive` (1 이상) | `ProblemDetailResponse`<br><br>**ProblemDetailResponse 구조:**<br>- `id` (String): 문제 ID<br>- `title` (String): 문제 제목<br>- `category` (String): 문제 카테고리<br>- `difficulty` (String): 난이도 티어명 (예: "BRONZE", "SILVER")<br>- `difficultyLevel` (Int): Solved.ac 난이도 레벨 (1-30)<br>- `url` (String): 문제 URL<br>- `descriptionHtml` (String, nullable): 문제 본문 HTML<br>- `inputDescriptionHtml` (String, nullable): 입력 설명 HTML<br>- `outputDescriptionHtml` (String, nullable): 출력 설명 HTML<br>- `sampleInputs` (List<String>, nullable): 샘플 입력 리스트<br>- `sampleOutputs` (List<String>, nullable): 샘플 출력 리스트<br>- `tags` (List<String>): 알고리즘 분류 태그 리스트 | None |

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

**예시 요청 (문제 상세 조회):**
```http
GET /api/v1/problems/1000
```

**예시 응답 (문제 상세 조회):**
```json
{
  "id": "1000",
  "title": "A+B",
  "category": "IMPLEMENTATION",
  "difficulty": "BRONZE",
  "difficultyLevel": 3,
  "url": "https://www.acmicpc.net/problem/1000",
  "descriptionHtml": "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>",
  "inputDescriptionHtml": "<p>첫째 줄에 A와 B가 주어진다. (0 < A, B < 10)</p>",
  "outputDescriptionHtml": "<p>첫째 줄에 A+B를 출력한다.</p>",
  "sampleInputs": ["1 2"],
  "sampleOutputs": ["3"],
  "tags": ["implementation", "arithmetic"]
}
```

**에러 응답 예시 (문제를 찾을 수 없음):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "PROBLEM_NOT_FOUND",
  "message": "문제를 찾을 수 없습니다. problemId=99999"
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
| POST | `/api/v1/retrospectives` | 학생이 문제 풀이 후 회고를 작성합니다. 이미 해당 문제에 대한 회고가 있으면 수정됩니다. **보안:** 쿼리 파라미터의 `studentId`와 JWT 토큰의 사용자 정보가 일치해야 합니다. 일치하지 않으면 403 Forbidden이 반환됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Query Parameters:**<br>- `studentId` (String, required): 학생 ID<br>- `problemId` (String, required): 문제 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상)<br>- `summary` (String, required): 한 줄 요약<br>  - 유효성: `@NotBlank`, `@Size(max=200)` (200자 이하)<br>  - 필수 항목<br>- `resultType` (ProblemResult, optional): 풀이 결과 타입 (SUCCESS/FAIL/TIME_OVER)<br>  - 사용자가 직접 선택한 결과임을 명시<br>  - null 허용 (선택사항)<br>- `solvedCategory` (String, optional): 사용자가 선택한 풀이 전략(알고리즘) 태그<br>  - 유효성: `@Size(max=50)` (50자 이하)<br>  - 예: "BruteForce", "Greedy" 등<br>  - null 허용 (선택사항)<br>- `solveTime` (String, optional): 풀이 소요 시간<br>  - 유효성: `@Size(max=50)` (50자 이하)<br>  - 예: "15m 30s" 또는 초 단위 문자열<br>  - null 허용 (선택사항) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>- `id` (String): 회고 ID<br>- `studentId` (String): 학생 ID<br>- `problemId` (String): 문제 ID<br>- `content` (String): 회고 내용<br>- `summary` (String, nullable): 한 줄 요약<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식)<br>- `isBookmarked` (Boolean): 북마크 여부<br>- `mainCategory` (String, nullable): 주요 알고리즘 카테고리<br>- `solutionResult` (String, nullable): 풀이 결과 (SUCCESS/FAIL/TIME_OVER)<br>- `solvedCategory` (String, nullable): 사용자가 선택한 풀이 전략 태그<br>- `solveTime` (String, nullable): 풀이 소요 시간 | JWT Token |
| GET | `/api/v1/retrospectives` | 검색 조건에 따라 회고 목록을 조회합니다. 키워드, 카테고리, 북마크 여부로 필터링할 수 있으며, 페이징을 지원합니다. | **Query Parameters:**<br>- `keyword` (String, optional): 검색 키워드 (내용 또는 문제 ID)<br>- `category` (String, optional): 카테고리 필터 (예: "DFS", "DP")<br>- `isBookmarked` (Boolean, optional): 북마크 여부 (true인 경우만 필터링)<br>- `studentId` (String, optional): 학생 ID 필터<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 10): 페이지 크기<br>  - 유효성: `@Positive` (1 이상)<br>- `sort` (String, optional): 정렬 기준 (예: "createdAt,desc" 또는 "createdAt,asc")<br>  - 기본값: "createdAt,desc" | `RetrospectivePageResponse`<br><br>**RetrospectivePageResponse 구조:**<br>- `content` (List<RetrospectiveResponse>): 회고 목록<br>- `totalElements` (Long): 전체 회고 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | None |
| GET | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 조회합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>(위와 동일) | None |
| POST | `/api/v1/retrospectives/{retrospectiveId}/bookmark` | 회고의 북마크 상태를 토글합니다. | **Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `BookmarkToggleResponse`<br><br>**BookmarkToggleResponse 구조:**<br>- `isBookmarked` (Boolean): 변경된 북마크 상태 | None |
| PATCH | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 수정합니다. **보안:** JWT 토큰의 사용자가 회고의 소유자인지 검증합니다. 소유자가 아니면 403 Forbidden이 반환됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID<br><br>**Request Body:**<br>`RetrospectiveRequest`<br>- `content` (String, required): 회고 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10)` (10자 이상)<br>- `summary` (String, required): 한 줄 요약<br>  - 유효성: `@NotBlank`, `@Size(max=200)` (200자 이하)<br>  - 필수 항목<br>- `resultType` (ProblemResult, optional): 풀이 결과 타입 (SUCCESS/FAIL/TIME_OVER)<br>  - null 허용 (선택사항)<br>- `solvedCategory` (String, optional): 사용자가 선택한 풀이 전략(알고리즘) 태그<br>  - 유효성: `@Size(max=50)` (50자 이하)<br>  - null 허용 (선택사항)<br>- `solveTime` (String, optional): 풀이 소요 시간<br>  - 유효성: `@Size(max=50)` (50자 이하)<br>  - null 허용 (선택사항) | `RetrospectiveResponse`<br><br>**RetrospectiveResponse 구조:**<br>(위와 동일) | JWT Token |
| DELETE | `/api/v1/retrospectives/{retrospectiveId}` | 회고 ID로 회고를 삭제합니다. **보안:** JWT 토큰의 사용자가 회고의 소유자인지 검증합니다. 소유자가 아니면 403 Forbidden이 반환됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `retrospectiveId` (String, required): 회고 ID | `204 No Content` (응답 본문 없음) | JWT Token |
| POST | `/api/v1/retrospectives/template/static` | 정적 템플릿을 반환합니다. `RETROSPECTIVE_STANDARDS.md`의 **불변 목차(1~5)** 구조를 포함한 순수 정적 마크다운을 제공합니다. **회고 템플릿은 AI가 생성하지 않으며**, 사용자가 목차를 보면서 내용을 채워넣을 수 있도록 설계됩니다. 문자열 마지막에는 반드시 아래 footer가 붙습니다:<br><br>```<br>---<br>Generated by DidimLog<br>``` | **Request Body:**<br>`StaticTemplateRequest`<br>- `code` (String, required): 사용자 코드<br>- `problemId` (String, required): 문제 ID<br>- `isSuccess` (Boolean, required): 풀이 성공 여부<br>- `errorMessage` (String, optional): 에러 메시지 (실패 시) | `TemplateResponse`<br><br>**TemplateResponse 구조:**<br>- `template` (String): 마크다운 형식의 템플릿 문자열 (footer 포함) | None |

**예시 요청 (회고 작성 - 성공 케이스):**
```http
POST /api/v1/retrospectives?studentId=student-123&problemId=1000
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다.",
  "summary": "두 수의 합을 구하는 기본 구현 문제",
  "resultType": "SUCCESS",
  "solvedCategory": "Implementation"
}
```

**에러 응답 예시 (쿼리 파라미터와 JWT 토큰 불일치):**
```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "ACCESS_DENIED",
  "message": "회고를 작성할 권한이 없습니다. studentId=student-456"
}
```

**예시 요청 (회고 작성 - 실패 케이스):**
```http
POST /api/v1/retrospectives?studentId=student-123&problemId=1000
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
POST /api/v1/retrospectives?studentId=student-123&problemId=1000
Content-Type: application/json

{
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다. 입력을 받아서 더하는 로직을 작성했습니다."
}
```

**예시 요청 (회고 작성 - 풀이 시간 포함):**
```http
POST /api/v1/retrospectives?studentId=student-123&problemId=1000
Content-Type: application/json

{
  "content": "이 문제는 두 수의 합을 구하는 간단한 구현 문제였습니다.",
  "summary": "두 수의 합을 구하는 기본 구현 문제",
  "resultType": "SUCCESS",
  "solvedCategory": "Implementation",
  "solveTime": "15m 30s"
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

**예시 요청 (회고 수정):**
```http
PATCH /api/v1/retrospectives/retrospective-123
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "content": "수정된 회고 내용입니다. 더 자세한 분석을 추가했습니다.",
  "summary": "수정된 한 줄 요약",
  "resultType": "SUCCESS",
  "solvedCategory": "DFS",
  "solveTime": "20m 15s"
}
```

**예시 응답 (회고 수정):**
```json
{
  "id": "retrospective-123",
  "studentId": "student-123",
  "problemId": "1000",
  "content": "수정된 회고 내용입니다. 더 자세한 분석을 추가했습니다.",
  "summary": "수정된 한 줄 요약",
  "createdAt": "2024-01-15T10:30:00",
  "isBookmarked": false,
  "mainCategory": null,
  "solutionResult": "SUCCESS",
  "solvedCategory": "DFS",
  "solveTime": "20m 15s"
}
```

**에러 응답 예시 (소유자가 아닌 경우):**
```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "ACCESS_DENIED",
  "message": "회고 소유자가 아닙니다. studentId=attacker-456"
}
```

**예시 요청 (회고 삭제):**
```http
DELETE /api/v1/retrospectives/retrospective-123
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (회고 삭제):**
```http
HTTP/1.1 204 No Content
```

**예시 요청 (정적 템플릿 생성 - 성공 케이스):**
```http
POST /api/v1/retrospectives/template/static
Content-Type: application/json

{
  "code": "def solve(a, b):\n    return a + b",
  "problemId": "1000",
  "isSuccess": true
}
```

**예시 응답 (정적 템플릿 생성 - 성공 케이스):**
```json
{
  "template": "# 🏆 [백준/BOJ] 1000번 A+B (PYTHON) 해결 회고\n\n## 🔑 학습 키워드\n\n- 구현\n- BRONZE 3\n\n## 1. 접근 방법 (Approach)\n\n- 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?\n- 풀이의 핵심 로직을 한 줄로 요약해 보세요.\n\n## 2. 복잡도 분석 (Complexity)\n\n- 시간 복잡도: O(?)\n- 공간 복잡도: O(?)\n\n## 3. 리팩토링 포인트 (Refactoring)\n\n- 개선할 수 있는 변수/함수명, 중복 제거, 로직 단순화 포인트를 적어보세요.\n\n## 4. 다른 풀이와 비교 (Comparison)\n\n- 다른 사람의 풀이(또는 표준 풀이)와 비교해서 내 풀이의 장단점을 정리해보세요.\n\n## 5. 다음 액션 (Next)\n\n- 다음에 같은 유형을 만나면 어떤 점을 더 잘할지 한 줄로 적어보세요.\n\n## 제출한 코드\n\n```python\ndef solve(a, b):\n    return a + b\n```\n\n---\nGenerated by DidimLog"
}
```

**예시 요청 (정적 템플릿 생성 - 실패 케이스):**
```http
POST /api/v1/retrospectives/template/static
Content-Type: application/json

{
  "code": "public class Solution {\n    public int solve(int a, int b) {\n        return a - b;\n    }\n}",
  "problemId": "1000",
  "isSuccess": false,
  "errorMessage": "틀렸습니다"
}
```

**예시 응답 (정적 템플릿 생성 - 실패 케이스):**
```json
{
  "template": "# 💥 [백준/BOJ] 1000번 A+B (JAVA) 오답 노트\n\n## 🔑 학습 키워드\n\n- 다이나믹 프로그래밍\n- BRONZE 3\n\n## 1. 실패 현상 (Symptom)\n\n- 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과, 틀렸습니다, 런타임 에러)\n- 테스트 케이스 중 통과하지 못한 예시가 있나요?\n\n## 2. 나의 접근 (My Attempt)\n\n- 어떤 로직으로 풀려고 시도했나요?\n\n## 3. 원인 추정 (Root Cause)\n\n- 왜 실패했다고 생각하나요? (논리/구현/복잡도/입출력 등)\n\n## 4. 반례/재현 케이스 (Counter Example)\n\n- 내 코드를 깨뜨리는 입력을 적어보세요.\n\n## 5. 다음 시도 계획 (Next)\n\n- 다음 시도에서 바꿀 점을 체크리스트로 적어보세요.\n\n## 제출한 코드\n\n```java\npublic class Solution {\n    public int solve(int a, int b) {\n        return a - b;\n    }\n}\n```\n\n## 에러 로그\n\n```text\n틀렸습니다\n```\n\n---\nGenerated by DidimLog"
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

## LogController

코딩 로그(Log) 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/logs` | 새로운 코딩 로그를 생성합니다. 생성된 로그 ID를 반환하며, 이후 AI 리뷰 생성에 사용할 수 있습니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`LogCreateRequest`<br>- `title` (String, required): 로그 제목<br>  - 유효성: `@NotBlank`<br>- `content` (String, required): 로그 내용<br>  - 유효성: `@NotBlank` (빈 문자열인 경우 서버에서 공백 문자로 기본값 처리)<br>- `code` (String, required): 사용자 코드<br>  - 유효성: `@NotBlank`<br>- `isSuccess` (Boolean, optional): 풀이 성공 여부<br>  - `true`: 성공한 코드 (AI 리뷰는 개선 제안 중심)<br>  - `false`: 실패한 코드 (AI 리뷰는 버그 분석 중심)<br>  - `null` (기본값): 미제출 또는 알 수 없음 (일반 코드 리뷰) | `LogResponse`<br><br>**LogResponse 구조:**<br>- `id` (String): 생성된 로그 ID | JWT Token |
| POST | `/api/v1/logs/{logId}/ai-review` | 로그 엔티티에서 **코드와 언어를 자동으로 추출**하여 AI 한 줄 리뷰를 생성하거나 조회합니다. 언어는 코드 내용을 분석하여 자동 감지됩니다.<br><br>**지원 언어:** C, CPP, CSHARP, GO, JAVA, JAVASCRIPT, KOTLIN, PYTHON, R, RUBY, SCALA, SWIFT, TEXT (백준 온라인 저지 지원 언어와 동기화)<br><br>**AI 모델:** Gemini 2.5 Flash<br><br>**응답 언어:** 한국어 (모든 리뷰는 한국어로 제공)<br><br>**프롬프트 (성공/실패 정보에 따라 차별화):**<br>- **성공한 코드 (`isSuccess = true`)**: "이 코드는 성공적으로 실행되었습니다. 이 {language} 코드를 분석하고 시간 복잡도 개선이나 코드 품질 향상을 위한 제안에 초점을 맞춰주세요."<br>- **실패한 코드 (`isSuccess = false`)**: "이 코드는 실행에 실패했습니다. 이 {language} 코드를 분석하고 실패 원인 분석이나 버그 수정을 위한 구체적인 피드백을 제공해주세요."<br>- **미제출 (`isSuccess = null`)**: "이 {language} 코드를 분석하고 시간 복잡도나 클린 코드 원칙에 초점을 맞춘 도움이 되는 한 줄 리뷰를 제공하세요."<br><br>**비용 절감 로직:**<br>- DB의 `aiReview`가 이미 존재하면 **외부 AI 호출 없이** 즉시 반환합니다. (비용 0원)<br>- 코드가 2000자를 초과하면 프롬프트 입력을 2000자까지만 잘라서 사용합니다.<br>- 코드가 10자 미만이면 AI 호출 없이 기본 메시지를 반환합니다. (응답: "코드가 너무 짧아 분석할 수 없습니다")<br><br>**타임아웃 및 에러 처리:**<br>- AI 생성 타임아웃: 30초 (30초 초과 시 `AI_GENERATION_TIMEOUT` 에러 반환)<br>- AI 생성 실패 시 `AI_GENERATION_FAILED` 에러 반환<br><br>**중복 호출 방지(멀티 인스턴스):**<br>- 동일 `logId`에 대해 동시에 요청이 들어오면, MongoDB의 원자적 락으로 **외부 AI 호출은 1회만** 수행됩니다.<br>- 락이 잡혀 있고 아직 결과가 없으면 아래 메시지를 반환할 수 있습니다: `AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요.` | **Path Variables:**<br>- `logId` (String, required): 로그 ID | `AiReviewResponse`<br>- `review` (String): 한 줄 리뷰 (한국어) 또는 안내 메시지<br>- `cached` (Boolean): 캐시 히트 여부 | None |
| POST | `/api/v1/logs/{logId}/feedback` | AI 리뷰에 대한 사용자 피드백을 제출합니다. LIKE 또는 DISLIKE를 선택할 수 있으며, DISLIKE의 경우 이유를 함께 제출할 수 있습니다. 피드백은 AI 리뷰 품질 개선을 위해 사용됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Path Variables:**<br>- `logId` (String, required): 로그 ID<br><br>**Request Body:**<br>`LogFeedbackRequest`<br>- `status` (AiFeedbackStatus, required): 피드백 상태<br>  - `LIKE`: 긍정적 피드백<br>  - `DISLIKE`: 부정적 피드백<br>  - 유효성: `@NotNull`<br>- `reason` (String, optional): 부정적 피드백의 이유<br>  - DISLIKE 선택 시 제공 가능<br>  - 예: "INACCURATE", "GENERIC", "NOT_HELPFUL" 등 | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): "피드백이 제출되었습니다." | JWT Token |

**예시 요청 (로그 생성):**
```http
POST /api/v1/logs
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "title": "Problem 1000 Solution",
  "content": "",
  "code": "public class Solution {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, World!\");\n    }\n}",
  "isSuccess": true
}
```

**예시 응답 (로그 생성):**
```json
{
  "id": "log-123"
}
```

**에러 응답 예시 (로그 생성 - 필수 필드 누락):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "title: 제목은 필수입니다."
}
```

**예시 응답 (AI 한 줄 리뷰 - 캐시됨):**
```json
{
  "review": "cached review",
  "cached": true
}
```

**예시 응답 (AI 한 줄 리뷰 - 새로 생성):**
```json
{
  "review": "한 줄 리뷰: 핵심 로직은 좋지만 함수 분리를 고려해보세요.",
  "cached": false
}
```

**예시 응답 (AI 한 줄 리뷰 - 생성 중):**
```json
{
  "review": "AI review is being generated. Please retry shortly.",
  "cached": false
}
```

**에러 응답 예시 (AI 생성 실패):**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "AI_GENERATION_FAILED",
  "message": "AI 리뷰 생성에 실패했습니다. 잠시 후 다시 시도해주세요."
}
```

**예시 요청 (AI 리뷰 피드백 제출 - LIKE):**
```http
POST /api/v1/logs/log-123/feedback
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "status": "LIKE"
}
```

**예시 요청 (AI 리뷰 피드백 제출 - DISLIKE):**
```http
POST /api/v1/logs/log-123/feedback
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "status": "DISLIKE",
  "reason": "INACCURATE"
}
```

**예시 응답 (피드백 제출 성공):**
```json
{
  "message": "피드백이 제출되었습니다."
}
```

**에러 응답 예시 (로그를 찾을 수 없음):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "COMMON_RESOURCE_NOT_FOUND",
  "message": "로그를 찾을 수 없습니다. logId=non-existent"
}
```

**에러 응답 예시 (AI 사용량 제한 초과 - 사용자):**
```json
{
  "status": 429,
  "error": "Too Many Requests",
  "code": "AI_USER_LIMIT_EXCEEDED",
  "message": "일일 AI 사용 횟수(5회)를 초과했습니다. 내일 다시 이용해주세요."
}
```

**에러 응답 예시 (AI 사용량 제한 초과 - 전역):**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "AI_GLOBAL_LIMIT_EXCEEDED",
  "message": "현재 서비스 이용량이 많아 AI 기능이 일시 중지되었습니다."
}
```

**에러 응답 예시 (AI 서비스 비활성화):**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "AI_SERVICE_DISABLED",
  "message": "AI 서비스가 일시 중지되었습니다."
}
```

---

## MemberController

회원 닉네임 관리 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/members/check-nickname` | 닉네임이 **유효하고** 중복이 아니면 `true`, 그렇지 않으면 `false`를 반환합니다. (유효성 검증 + 중복 체크) | **Query Parameters:**<br>- `nickname` (String, required): 닉네임<br>  - 유효성: `@NotBlank`<br>  - **닉네임 정책:**<br>    - 길이: 2~12<br>    - 허용: 영문/숫자/완성형 한글(가-힣)/특수문자(., _, -)<br>    - 금지: 공백/한글 자모(ㄱ-ㅎ, ㅏ-ㅣ)/기타 특수문자/예약어(admin, manager)<br>    - 정규식: `^[a-zA-Z0-9가-힣._-]{2,12}$` | `Boolean` | None |
| PATCH | `/api/v1/members/me/nickname` | 로그인한 사용자의 닉네임을 변경합니다. 변경 시 **유효성 + 중복 검사**를 수행합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`UpdateMyNicknameRequest`<br>- `nickname` (String, required)<br>  - 유효성: `@NotBlank`<br>  - 닉네임 정책은 위와 동일 | `204 No Content` | JWT Token |

**예시 요청 (닉네임 사용 가능 여부):**
```http
GET /api/v1/members/check-nickname?nickname=user_01
```

**예시 응답 (사용 가능):**
```json
true
```

**예시 응답 (사용 불가):**
```json
false
```

**예시 요청 (내 닉네임 변경):**
```http
PATCH /api/v1/members/me/nickname
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "nickname": "user_01"
}
```

**예시 응답 (성공):**
```http
204 No Content
```

---

## StudentController

학생 프로필 관리 관련 API를 제공합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| PATCH | `/api/v1/students/me` | 학생의 닉네임, 비밀번호, 주 언어를 수정합니다. 각 필드는 선택적으로 변경할 수 있으며, 비밀번호 변경 시 현재 비밀번호 검증이 필요합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`UpdateProfileRequest`<br>- `nickname` (String, optional): 변경할 닉네임<br>  - **닉네임 정책:**<br>    - 길이: 2~12<br>    - 허용: 영문/숫자/완성형 한글(가-힣)/특수문자(., _, -)<br>    - 금지: 공백/한글 자모(ㄱ-ㅎ, ㅏ-ㅣ)/기타 특수문자/예약어(admin, manager)<br>    - 정규식: `^[a-zA-Z0-9가-힣._-]{2,12}$`<br>  - null이면 변경하지 않음<br>- `currentPassword` (String, optional): 현재 비밀번호<br>  - 비밀번호 변경 시 필수 입력<br>- `newPassword` (String, optional): 새로운 비밀번호<br>  - 유효성: `@Size(min=8)` (8자 이상)<br>  - 비밀번호 정책: AuthController의 비밀번호 정책과 동일<br>  - null이면 변경하지 않음<br>- `primaryLanguage` (PrimaryLanguage, optional): 주로 사용하는 프로그래밍 언어<br>  - 가능한 값: `C`, `CPP`, `CSHARP`, `GO`, `JAVA`, `JAVASCRIPT`, `KOTLIN`, `PYTHON`, `R`, `RUBY`, `SCALA`, `SWIFT`, `TEXT`<br>  - 백준 온라인 저지 지원 언어와 동기화 (총 13개 언어)<br>  - null이면 변경하지 않음 | `204 No Content` (성공 시) | JWT Token |
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

**예시 요청 (주 언어 변경):**
```http[PR_PROMPT.md](../../../../.cursor/worktrees/didim-log/tnp/PR_PROMPT.md)
PATCH /api/v1/students/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "primaryLanguage": "JAVA"
}
```

**예시 요청 (닉네임과 주 언어 모두 변경):**
```http
PATCH /api/v1/students/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "nickname": "newNickname",
  "primaryLanguage": "KOTLIN"
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
| GET | `/api/v1/statistics` | 학생의 활동 히트맵(Heatmap), 카테고리별 분포, 알고리즘 카테고리 통계, 누적 풀이 수를 포함한 통계 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 | `StatisticsResponse`<br><br>**StatisticsResponse 구조:**<br>- `monthlyHeatmap` (List<HeatmapDataResponse>): 최근 365일간의 활동 히트맵 데이터 (오늘 포함하여 정확히 365일)<br>- `categoryDistribution` (Map<String, Int>): 카테고리별 풀이 통계 (현재는 빈 맵, 향후 구현 예정)<br>- `algorithmCategoryDistribution` (Map<String, Int>): 알고리즘 카테고리별 사용 통계 (Retrospective의 solvedCategory 기준)<br>- `topUsedAlgorithms` (List<TopUsedAlgorithmResponse>): 가장 많이 사용한 알고리즘 상위 3개<br>- `totalSolvedCount` (Int): 누적 풀이 수<br>- `totalRetrospectives` (Long): 총 회고 수<br>- `averageSolveTime` (Double): 평균 풀이 시간 (초 단위)<br>- `successRate` (Double): 성공률 (0.0 ~ 100.0, 소수점 첫째 자리까지 반올림)<br>- `tagRadarData` (List<TagStatResponse>): 레이더 차트용 태그별 통계 (상위 5개)<br>- `weaknessAnalysis` (WeaknessAnalysisResponse, nullable): 취약점 분석 데이터 (실패한 회고가 없으면 null)<br><br>**HeatmapDataResponse 구조:**<br>- `date` (String): 날짜 (ISO 8601 형식, 예: "2024-01-15")<br>- `count` (Int): 해당 날짜의 풀이 수<br>- `problemIds` (List<String>): 해당 날짜에 풀이한 문제 ID 목록 (중복 제거됨)<br><br>**TopUsedAlgorithmResponse 구조:**<br>- `name` (String): 알고리즘 이름 (예: "DFS", "DP", "Greedy")<br>- `count` (Int): 사용 횟수<br><br>**TagStatResponse 구조:**<br>- `tag` (String): 태그명<br>- `count` (Int): 해당 태그로 풀이한 문제 수<br>- `fullMark` (Int): 그래프 스케일링용 최대 카운트 값<br><br>**WeaknessAnalysisResponse 구조:**<br>- `totalFailures` (Int): 총 실패 횟수<br>- `topCategory` (String, nullable): 가장 빈번한 실패 카테고리<br>- `topCategoryCount` (Int): 가장 빈번한 실패 카테고리의 실패 횟수<br>- `topReason` (String, nullable): 가장 빈번한 실패 원인 (FAIL 또는 TIME_OVER)<br>- `categoryFailures` (List<CategoryFailureResponse>): 카테고리별 실패 분포 (상위 8개)<br><br>**CategoryFailureResponse 구조:**<br>- `category` (String): 카테고리명<br>- `count` (Int): 해당 카테고리의 실패 횟수 | JWT Token |

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
      "problemIds": ["1003", "1004"]
    },
    {
      "date": "2024-01-17",
      "count": 1,
      "problemIds": ["1005"]
    }
  ],
  "categoryDistribution": {},
  "algorithmCategoryDistribution": {
    "DFS": 15,
    "DP": 12,
    "Greedy": 8,
    "BFS": 5
  },
  "topUsedAlgorithms": [
    {
      "name": "DFS",
      "count": 15
    },
    {
      "name": "DP",
      "count": 12
    },
    {
      "name": "Greedy",
      "count": 8
    }
  ],
  "totalSolvedCount": 150,
  "totalRetrospectives": 42,
  "averageSolveTime": 1800.5,
  "successRate": 72.5,
  "tagRadarData": [
    {
      "tag": "DFS",
      "count": 15,
      "fullMark": 15
    },
    {
      "tag": "DP",
      "count": 12,
      "fullMark": 15
    },
    {
      "tag": "Greedy",
      "count": 8,
      "fullMark": 15
    },
    {
      "tag": "BFS",
      "count": 5,
      "fullMark": 15
    },
    {
      "tag": "Hash",
      "count": 3,
      "fullMark": 15
    }
  ],
  "weaknessAnalysis": {
    "totalFailures": 10,
    "topCategory": "GRAPH",
    "topCategoryCount": 5,
    "topReason": "FAIL",
    "categoryFailures": [
      {
        "category": "GRAPH",
        "count": 5
      },
      {
        "category": "DP",
        "count": 3
      },
      {
        "category": "GREEDY",
        "count": 2
      }
    ]
  }
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
| GET | `/api/v1/admin/users` | 페이징을 적용하여 전체 회원 목록을 조회합니다. 검색어와 날짜 범위 필터를 지원합니다. Rating 기준 내림차순으로 정렬됩니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상)<br>- `search` (String, optional): 검색어 (닉네임, BOJ ID, 이메일)<br>  - 대소문자 구분 없이 부분 일치 검색<br>- `startDate` (String, optional): 가입 시작일 (ISO 8601 형식, 예: "2024-01-01")<br>  - 해당 날짜 00:00:00 이후 가입한 회원만 조회<br>- `endDate` (String, optional): 가입 종료일 (ISO 8601 형식, 예: "2024-12-31")<br>  - 해당 날짜 23:59:59 이전 가입한 회원만 조회 | `Page<AdminUserResponse>`<br><br>**AdminUserResponse 구조:**<br>- `id` (String): 학생 ID<br>- `nickname` (String): 닉네임<br>- `bojId` (String, nullable): BOJ ID (소셜 로그인 사용자는 null)<br>- `email` (String, nullable): 이메일 (소셜 로그인 사용자만 존재)<br>- `provider` (String): 인증 제공자 (BOJ, GOOGLE, GITHUB, NAVER)<br>- `role` (String): 사용자 권한 (GUEST, USER, ADMIN)<br>- `rating` (Int): Solved.ac Rating (점수)<br>- `currentTier` (String): 현재 티어명 (예: "GOLD")<br>- `consecutiveSolveDays` (Int): 연속 풀이 일수<br>- `solvedCount` (Long): 해결한 문제 수 (SUCCESS인 Solution 개수)<br>- `retrospectiveCount` (Long): 작성한 회고 수<br><br>**Page 구조:**<br>- `content` (List<AdminUserResponse>): 회원 목록<br>- `totalElements` (Long): 전체 회원 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/users/{studentId}` | 특정 회원을 강제로 탈퇴시킵니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `studentId` (String, required): 학생 ID | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): 응답 메시지 ("회원이 성공적으로 탈퇴되었습니다.") | JWT Token (ADMIN) |
| PATCH | `/api/v1/admin/users/{studentId}` | 사용자 권한(Role), 닉네임, BOJ ID를 선택적으로 수정합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AdminUserUpdateDto` (optional fields)<br>- `role` (String, optional): ROLE_USER/ROLE_ADMIN<br>- `nickname` (String, optional)<br>- `bojId` (String, optional) | `204 No Content` (응답 본문 없음) | JWT Token (ADMIN) |

**예시 요청 (전체 회원 목록 조회):**
```http
GET /api/v1/admin/users?page=1&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (검색 포함):**
```http
GET /api/v1/admin/users?page=1&size=20&search=user1
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
  "message": "접근 권한이 없습니다."
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
| POST | `/api/v1/admin/notices` | 관리자가 공지사항을 작성합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`NoticeCreateRequest`<br>- `title` (String, required): 제목<br>  - 유효성: `@NotBlank`, `@Size(max=200)` (200자 이하)<br>- `content` (String, required): 내용<br>  - 유효성: `@NotBlank`, `@Size(min=10, max=10000)` (10자 이상 10000자 이하)<br>- `isPinned` (Boolean, optional): 상단 고정 여부<br>  - 기본값: false | `NoticeResponse`<br><br>**NoticeResponse 구조:**<br>- `id` (String): 공지사항 ID<br>- `title` (String): 제목<br>- `content` (String): 내용<br>- `isPinned` (Boolean): 상단 고정 여부<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식)<br>- `updatedAt` (LocalDateTime): 수정 일시 (ISO 8601 형식) | JWT Token (ADMIN) |
| POST | `/api/v1/admin/quotes` | 새로운 명언을 추가합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`QuoteCreateRequest`<br>- `content` (String, required): 명언 내용<br>  - 유효성: `@NotBlank`<br>- `author` (String, required): 저자명<br>  - 유효성: `@NotBlank` | `QuoteResponse`<br><br>**QuoteResponse 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/quotes/{quoteId}` | 특정 명언을 삭제합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `quoteId` (String, required): 명언 ID | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): 응답 메시지 ("명언이 성공적으로 삭제되었습니다.") | JWT Token (ADMIN) |
| GET | `/api/v1/admin/feedbacks` | 페이징을 적용하여 피드백 목록을 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상) | `Page<FeedbackResponse>`<br><br>**FeedbackResponse 구조:**<br>- `id` (String): 피드백 ID<br>- `writerId` (String): 작성자 ID (Student ID)<br>- `bojId` (String, nullable): 작성자 BOJ ID (Student를 찾을 수 없는 경우 null)<br>- `content` (String): 피드백 내용<br>- `type` (String): 피드백 유형 ("BUG", "SUGGESTION")<br>- `status` (String): 처리 상태 ("PENDING", "COMPLETED")<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시<br><br>**Page 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| PATCH | `/api/v1/admin/feedbacks/{feedbackId}/status` | 피드백의 처리 상태를 변경합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `feedbackId` (String, required): 피드백 ID<br><br>**Request Body:**<br>`FeedbackStatusUpdateRequest`<br>- `status` (FeedbackStatus, required): 새로운 상태 ("PENDING", "COMPLETED") | `FeedbackResponse`<br><br>**FeedbackResponse 구조:**<br>(위와 동일) | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/feedbacks/{feedbackId}` | 완료된 피드백을 삭제합니다. 완료되지 않은 피드백은 삭제할 수 없습니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `feedbackId` (String, required): 피드백 ID | `204 No Content` (성공 시)<br><br>**에러 응답:**<br>- `400 Bad Request`: 완료되지 않은 피드백은 삭제할 수 없음<br>- `404 Not Found`: 피드백을 찾을 수 없음 | JWT Token (ADMIN) |

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

**예시 요청 (공지사항 작성):**
```http
POST /api/v1/admin/notices
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "title": "시스템 점검 안내",
  "content": "2024년 1월 20일 00:00 ~ 02:00 시스템 점검이 예정되어 있습니다.",
  "isPinned": true
}
```

**예시 응답 (공지사항 작성):**
```json
{
  "id": "notice-123",
  "title": "시스템 점검 안내",
  "content": "2024년 1월 20일 00:00 ~ 02:00 시스템 점검이 예정되어 있습니다.",
  "isPinned": true,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**에러 응답 예시 (제목이 200자 초과):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "title: 제목은 200자 이하여야 합니다."
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
      "bojId": "testuser",
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
  "bojId": "testuser",
  "content": "로그인 시 에러가 발생합니다.",
  "type": "BUG",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T11:00:00"
}
```

---

## AdminMemberController

관리자 전용 회원 관리 API를 제공합니다. ADMIN 권한이 필요합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| PUT | `/api/v1/admin/members/{memberId}` | 관리자가 특정 회원의 닉네임/비밀번호를 수정합니다. `password`가 제공되면 `PasswordEncoder`로 암호화 후 저장합니다. `nickname` 변경 시 유효성 및 중복 검사를 수행합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `memberId` (String, required): 회원 ID<br><br>**Request Body:**<br>`AdminMemberUpdateRequest`<br>- `nickname` (String, optional)<br>  - 닉네임 정책은 MemberController와 동일<br>- `password` (String, optional)<br>  - 제공된 경우 암호화 후 저장 | `204 No Content` | JWT Token (ADMIN) |

**예시 요청 (닉네임/비밀번호 변경):**
```http
PUT /api/v1/admin/members/member-1
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "nickname": "user_01",
  "password": "pw1234!"
}
```

**예시 응답 (성공):**
```http
204 No Content
```

---

## AdminLogController

관리자용 AI 리뷰 로그 조회 API를 제공합니다. ADMIN 권한이 필요합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/logs` | AI 리뷰 생성 로그를 페이징하여 조회합니다. BOJ ID로 필터링할 수 있습니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 20): 페이지 크기<br>  - 유효성: `@Positive` (1 이상)<br>- `bojId` (String, optional): 필터링할 BOJ ID | `Page<AdminLogResponse>`<br><br>**AdminLogResponse 구조:**<br>- `id` (String): 로그 ID<br>- `bojId` (String, nullable): AI 리뷰를 요청한 사용자의 BOJ ID<br>- `title` (String): 로그 제목<br>- `content` (String): 로그 내용<br>- `code` (String): 제출된 코드<br>- `aiReview` (String, nullable): AI가 생성한 한 줄 리뷰<br>- `aiReviewStatus` (String, nullable): AI 리뷰 상태 (COMPLETED, FAILED, IN_PROGRESS)<br>- `aiReviewDurationMillis` (Long, nullable): AI 리뷰 생성에 걸린 시간 (밀리초)<br>- `createdAt` (LocalDateTime): 로그 생성 일시<br><br>**Page 구조:**<br>- `content` (List<AdminLogResponse>): 로그 목록<br>- `totalElements` (Long): 전체 로그 수<br>- `totalPages` (Int): 전체 페이지 수<br>- `currentPage` (Int): 현재 페이지 번호<br>- `size` (Int): 페이지 크기<br>- `hasNext` (Boolean): 다음 페이지 존재 여부<br>- `hasPrevious` (Boolean): 이전 페이지 존재 여부 | JWT Token (ADMIN) |
| GET | `/api/v1/admin/logs/{logId}` | 특정 AI 리뷰 생성 로그의 상세 정보를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `logId` (String, required): 로그 ID | `AdminLogResponse`<br><br>**AdminLogResponse 구조:** (위와 동일) | JWT Token (ADMIN) |
| DELETE | `/api/v1/admin/logs/cleanup` | 지정된 일수 이상 된 로그를 삭제합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `olderThanDays` (Int, required): 기준일 (이보다 오래된 로그 삭제)<br>  - 유효성: `@Positive` (1 이상) | `LogCleanupResponse`<br><br>**LogCleanupResponse 구조:**<br>- `message` (String): 응답 메시지 (예: "100개의 로그가 삭제되었습니다.")<br>- `deletedCount` (Long): 삭제된 로그 수 | JWT Token (ADMIN) |

**예시 요청 (로그 목록 조회):**
```http
GET /api/v1/admin/logs?page=1&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (BOJ ID 필터링):**
```http
GET /api/v1/admin/logs?bojId=user123&page=1&size=20
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 요청 (로그 정리):**
```http
DELETE /api/v1/admin/logs/cleanup?olderThanDays=30
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (로그 정리):**
```json
{
  "message": "100개의 로그가 삭제되었습니다.",
  "deletedCount": 100
}
```

**예시 응답 (로그 목록 조회):**
```json
{
  "content": [
    {
      "id": "log-123",
      "bojId": "user123",
      "title": "Problem 1000 Solution",
      "content": "회고 내용",
      "code": "public class Solution { ... }",
      "aiReview": "코드가 명확하고 시간 복잡도 O(N)으로 최적입니다.",
      "aiReviewStatus": "COMPLETED",
      "aiReviewDurationMillis": 2706,
      "createdAt": "2024-01-15T10:30:00"
    }
  ],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20,
  "hasNext": true,
  "hasPrevious": false
}
```

---

## AdminDashboardController

관리자 대시보드 통계 관련 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/admin/dashboard/stats` | 총 회원 수, 오늘 가입한 회원 수, 총 해결된 문제 수, 오늘 작성된 회고 수를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `AdminDashboardStatsResponse`<br><br>**AdminDashboardStatsResponse 구조:**<br>- `totalUsers` (Long): 총 회원 수<br>- `todaySignups` (Long): 오늘 가입한 회원 수<br>- `totalSolvedProblems` (Long): 총 해결된 문제 수 (SUCCESS인 Solution 개수)<br>- `todayRetrospectives` (Long): 오늘 작성된 회고 수<br>- `aiMetrics` (AiMetricsResponse): AI 리뷰 생성 통계<br><br>**AiMetricsResponse 구조:**<br>- `averageDurationMillis` (Long, nullable): 평균 AI 생성 시간 (밀리초, null이면 아직 생성된 리뷰가 없음)<br>- `averageDurationSeconds` (Double, nullable): 평균 AI 생성 시간 (초, 소수점 2자리, null이면 아직 생성된 리뷰가 없음)<br>- `totalGeneratedCount` (Long): 총 생성된 AI 리뷰 수<br>- `timeoutCount` (Long): 타임아웃된 AI 리뷰 수<br>- `timeoutRate` (Double): 타임아웃 비율 (0.0 ~ 1.0) | JWT Token (ADMIN) |
| GET | `/api/v1/admin/dashboard/metrics` | 최근 30분~1시간 동안의 분당 요청 수(RPM)와 평균 응답 속도를 조회합니다. HandlerInterceptor를 활용하여 요청 시간을 측정하고 메모리에 시계열 데이터를 저장합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `minutes` (Int, optional, default: 30): 조회할 시간 범위 (분)<br>  - 유효성: `@Positive` (1 이상)<br>  - 권장값: 30~60분 | `PerformanceMetricsResponse`<br><br>**PerformanceMetricsResponse 구조:**<br>- `rpm` (Double): 분당 요청 수 (Requests Per Minute)<br>- `averageResponseTime` (Double): 평균 응답 시간 (밀리초)<br>- `timeRangeMinutes` (Int): 조회한 시간 범위 (분)<br>- `rpmTimeSeries` (List<TimeSeriesPointResponse>): RPM 시계열 데이터 (최대 30개 포인트)<br>- `latencyTimeSeries` (List<TimeSeriesPointResponse>): 응답 시간 시계열 데이터 (최대 30개 포인트)<br><br>**TimeSeriesPointResponse 구조:**<br>- `timestamp` (Long): Unix timestamp (초)<br>- `value` (Double): 값 | JWT Token (ADMIN) |
| GET | `/api/v1/admin/dashboard/chart` | 통계 카드 클릭 시 표시할 트렌드 차트 데이터를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `dataType` (String, required): 데이터 타입 (USER, SOLUTION, RETROSPECTIVE)<br>- `period` (String, required): 기간 (DAILY, WEEKLY, MONTHLY) | `ChartDataResponse`<br><br>**ChartDataResponse 구조:**<br>- `data` (List<ChartDataItem>): 차트 데이터 리스트<br><br>**ChartDataItem 구조:**<br>- `date` (String): 날짜 문자열 (형식은 period에 따라 다름)<br>- `value` (Long): 값 (누적 합계) | JWT Token (ADMIN) |

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

**예시 요청 (성능 메트릭 조회):**
```http
GET /api/v1/admin/dashboard/metrics?minutes=30
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (성능 메트릭 조회):**
```json
{
  "rpm": 45.5,
  "averageResponseTime": 125.3,
  "timeRangeMinutes": 30,
  "rpmTimeSeries": [
    {
      "timestamp": 1704067200,
      "value": 10.0
    },
    {
      "timestamp": 1704067260,
      "value": 15.0
    }
  ],
  "latencyTimeSeries": [
    {
      "timestamp": 1704067200,
      "value": 120.5
    },
    {
      "timestamp": 1704067260,
      "value": 130.2
    }
  ]
}
```

**예시 요청 (차트 데이터 조회):**
```http
GET /api/v1/admin/dashboard/chart?dataType=USER&period=DAILY
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (차트 데이터 조회):**
```json
{
  "data": [
    {
      "date": "2024-01-01",
      "value": 10
    },
    {
      "date": "2024-01-02",
      "value": 25
    },
    {
      "date": "2024-01-03",
      "value": 40
    }
  ]
}
```

**예시 요청 (AI 품질 통계 조회):**
```http
GET /api/v1/admin/dashboard/ai-quality
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (AI 품질 통계 조회):**
```json
{
  "totalFeedbackCount": 150,
  "positiveRate": 82.5,
  "negativeReasons": {
    "INACCURATE": 12,
    "GENERIC": 8,
    "NOT_HELPFUL": 6
  },
  "recentNegativeLogs": [
    {
      "id": "log-123",
      "aiReview": "코드가 명확하고 시간 복잡도 O(N)으로 최적입니다.",
      "codeSnippet": "def solve(arr):\n    return sum(arr)"
    },
    {
      "id": "log-456",
      "aiReview": "이 코드는 비효율적입니다.",
      "codeSnippet": "for i in range(n):\n    for j in range(n):\n        ..."
    }
  ]
}
```

---

## SystemController

시스템 제어 관련 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/admin/system/maintenance` | 서버를 끄지 않고 일반 사용자의 접근만 차단하는 유지보수 모드를 활성화/비활성화합니다. 전역 필터/인터셉터에서 이 플래그가 `true`일 때, ADMIN 권한이 없는 요청은 `503 Service Unavailable` 예외를 발생시킵니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`MaintenanceModeRequest`<br>- `enabled` (Boolean, required): 유지보수 모드 활성화 여부 | `MaintenanceModeResponse`<br><br>**MaintenanceModeResponse 구조:**<br>- `enabled` (Boolean): 현재 유지보수 모드 상태<br>- `message` (String): 응답 메시지 ("유지보수 모드가 활성화되었습니다." 또는 "유지보수 모드가 비활성화되었습니다.") | JWT Token (ADMIN) |
| GET | `/api/v1/admin/system/ai-status` | AI 서비스의 현재 상태(활성화 여부, 사용량, 제한값)를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `AiStatusResponse`<br><br>**AiStatusResponse 구조:**<br>- `isEnabled` (Boolean): AI 서비스 활성화 여부<br>- `todayGlobalUsage` (Int): 오늘의 전역 사용량<br>- `globalLimit` (Int): 전역 일일 제한<br>- `userLimit` (Int): 사용자 일일 제한 | JWT Token (ADMIN) |
| POST | `/api/v1/admin/system/ai-status` | AI 서비스를 수동으로 활성화 또는 비활성화합니다. 긴급 상황에서 서비스를 중지할 수 있습니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AiStatusUpdateRequest`<br>- `enabled` (Boolean, required): AI 서비스 활성화 여부 | `AiStatusResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/system/ai-limits` | AI 서비스의 전역 일일 제한 및 사용자 일일 제한을 동적으로 업데이트합니다. 서버 재시작 없이 즉시 적용됩니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AiLimitsUpdateRequest`<br>- `globalLimit` (Int, required, min: 1): 전역 일일 제한<br>- `userLimit` (Int, required, min: 1): 사용자 일일 제한 | `AiStatusResponse` | JWT Token (ADMIN) |
| GET | `/api/v1/admin/system/ai-status` | AI 서비스의 현재 상태(활성화 여부, 사용량, 제한값)를 조회합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `AiStatusResponse`<br><br>**AiStatusResponse 구조:**<br>- `isEnabled` (Boolean): AI 서비스 활성화 여부<br>- `todayGlobalUsage` (Int): 오늘의 전역 사용량<br>- `globalLimit` (Int): 전역 일일 제한<br>- `userLimit` (Int): 사용자 일일 제한 | JWT Token (ADMIN) |
| POST | `/api/v1/admin/system/ai-status` | AI 서비스를 수동으로 활성화 또는 비활성화합니다. 긴급 상황에서 서비스를 중지할 수 있습니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AiStatusUpdateRequest`<br>- `enabled` (Boolean, required): AI 서비스 활성화 여부 | `AiStatusResponse` | JWT Token (ADMIN) |
| POST | `/api/v1/admin/system/ai-limits` | AI 서비스의 전역 일일 제한 및 사용자 일일 제한을 동적으로 업데이트합니다. 서버 재시작 없이 즉시 적용됩니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Request Body:**<br>`AiLimitsUpdateRequest`<br>- `globalLimit` (Int, required, min: 1): 전역 일일 제한<br>- `userLimit` (Int, required, min: 1): 사용자 일일 제한 | `AiStatusResponse` | JWT Token (ADMIN) |

**예시 요청 (유지보수 모드 활성화):**
```http
POST /api/v1/admin/system/maintenance
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "enabled": true
}
```

**예시 응답 (유지보수 모드 활성화):**
```json
{
  "enabled": true,
  "message": "유지보수 모드가 활성화되었습니다."
}
```

**예시 요청 (유지보수 모드 비활성화):**
```http
POST /api/v1/admin/system/maintenance
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "enabled": false
}
```

**예시 응답 (유지보수 모드 비활성화):**
```json
{
  "enabled": false,
  "message": "유지보수 모드가 비활성화되었습니다."
}
```

**에러 응답 예시 (유지보수 모드 활성화 시 일반 사용자 접근):**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "MAINTENANCE_MODE",
  "message": "서비스가 일시적으로 점검 중입니다. 잠시 후 다시 시도해주세요."
}
```

---

## ProblemCollectorController

문제 데이터 수집 관련 API를 제공합니다. ADMIN 권한이 필요하며, JWT 토큰의 role이 ADMIN인 경우에만 접근 가능합니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/admin/problems/collect-metadata` | Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 수집하여 DB에 저장합니다. (Upsert 방식) | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Query Parameters:**<br>- `start` (Int, required): 시작 문제 ID<br>  - 유효성: `@Positive` (1 이상)<br>- `end` (Int, required): 종료 문제 ID (포함)<br>  - 유효성: `@Positive` (1 이상) | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): "문제 메타데이터 수집이 완료되었습니다."<br>- `range` (String): "start-end" 형식의 범위 문자열 | JWT Token (ADMIN) |
| POST | `/api/v1/admin/problems/collect-details` | DB에서 descriptionHtml이 null인 문제들의 상세 정보를 BOJ 사이트에서 크롤링하여 업데이트합니다. Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둡니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요) | `Map<String, String>`<br><br>**응답 구조:**<br>- `message` (String): "문제 상세 정보 크롤링이 완료되었습니다." | JWT Token (ADMIN) |

**예시 요청 (메타데이터 수집):**
```http
POST /api/v1/admin/problems/collect-metadata?start=1000&end=1100
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**예시 응답 (메타데이터 수집):**
```json
{
  "message": "문제 메타데이터 수집이 완료되었습니다.",
  "range": "1000-1100"
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
  "message": "문제 상세 정보 크롤링이 완료되었습니다."
}
```

**에러 응답 예시 (유효하지 않은 start/end 값):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_VALIDATION_FAILED",
  "message": "collectMetadata.start: 시작 문제 ID는 1 이상이어야 합니다."
}
```

**에러 응답 예시 (ADMIN 권한 없음):**
```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "ACCESS_DENIED",
  "message": "접근 권한이 없습니다."
}
```

---

## NoticeController

공지사항 관련 API를 제공합니다.

- **작성**: `AdminController`의 `POST /api/v1/admin/notices`
- **조회/수정/삭제**: `NoticeController`의 `/api/v1/notices` 하위 API

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| GET | `/api/v1/notices` | 공지사항 목록을 조회합니다. 상단 고정 공지(`isPinned=true`)가 먼저 오고, 그 다음 최신순으로 정렬됩니다. 페이징을 지원합니다. | **Query Parameters:**<br>- `page` (Int, optional, default: 1): 페이지 번호 (1부터 시작)<br>  - 유효성: `@Min(1)` (1 이상)<br>- `size` (Int, optional, default: 10): 페이지 크기<br>  - 유효성: `@Positive` (1 이상) | `Page<NoticeResponse>`<br><br>**NoticeResponse 구조:**<br>- `id` (String): 공지사항 ID<br>- `title` (String): 제목<br>- `content` (String): 내용<br>- `isPinned` (Boolean): 상단 고정 여부<br>- `createdAt` (LocalDateTime): 생성 일시 (ISO 8601 형식)<br>- `updatedAt` (LocalDateTime): 수정 일시 (ISO 8601 형식)<br><br>**Page 구조 (Spring Data Page 직렬화):**<br>- `content` (List<NoticeResponse>)<br>- `totalElements` (Long)<br>- `totalPages` (Int)<br>- `size` (Int)<br>- `number` (Int): 0부터 시작하는 현재 페이지 인덱스 | None |
| GET | `/api/v1/notices/{noticeId}` | 공지사항 ID로 공지사항을 조회합니다. | **Path Variables:**<br>- `noticeId` (String, required): 공지사항 ID | `NoticeResponse` | None |
| PATCH | `/api/v1/notices/{noticeId}` | 공지사항을 수정합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `noticeId` (String, required): 공지사항 ID<br><br>**Request Body:**<br>`NoticeUpdateRequest`<br>- `title` (String, optional): 제목<br>  - 유효성: `@Size(max=200)`<br>- `content` (String, optional): 내용<br>  - 유효성: `@Size(max=10000)`<br>- `isPinned` (Boolean, optional): 상단 고정 여부 | `NoticeResponse` | JWT Token (ADMIN) |
| DELETE | `/api/v1/notices/{noticeId}` | 공지사항을 삭제합니다. ADMIN 권한이 필요합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰 (ADMIN role 필요)<br><br>**Path Variables:**<br>- `noticeId` (String, required): 공지사항 ID | `204 No Content` | JWT Token (ADMIN) |

**예시 요청 (공지사항 목록 조회):**
```http
GET /api/v1/notices?page=1&size=10
```

**예시 응답 (공지사항 목록 조회):**
```json
{
  "content": [
    {
      "id": "notice-123",
      "title": "시스템 점검 안내",
      "content": "2024년 1월 20일 00:00 ~ 02:00 시스템 점검이 예정되어 있습니다.",
      "isPinned": true,
      "createdAt": "2024-01-15T10:30:00",
      "updatedAt": "2024-01-15T10:30:00"
    },
    {
      "id": "notice-456",
      "title": "새로운 기능 업데이트",
      "content": "회고 기능이 업데이트되었습니다.",
      "isPinned": false,
      "createdAt": "2024-01-14T09:00:00",
      "updatedAt": "2024-01-14T09:00:00"
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "size": 10,
  "number": 0
}
```

**예시 요청 (공지사항 상세 조회):**
```http
GET /api/v1/notices/notice-123
```

**예시 응답 (공지사항 상세 조회):**
```json
{
  "id": "notice-123",
  "title": "시스템 점검 안내",
  "content": "2024년 1월 20일 00:00 ~ 02:00 시스템 점검이 예정되어 있습니다.",
  "isPinned": true,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

**에러 응답 예시 (공지사항을 찾을 수 없음):**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "COMMON_RESOURCE_NOT_FOUND",
  "message": "공지사항을 찾을 수 없습니다. id=non-existent"
}
```

---

## FeedbackController

고객의 소리(피드백) 관련 API를 제공합니다. 사용자는 버그 리포트나 건의사항을 제출할 수 있습니다.

| Method | URI | 기능 설명 | Request | Response | Auth |
|--------|-----|----------|---------|----------|------|
| POST | `/api/v1/feedback` | 버그 리포트 또는 건의사항을 등록합니다. JWT 토큰에서 사용자 ID를 자동으로 추출합니다. | **Headers:**<br>- `Authorization: Bearer {token}` (required): JWT 토큰<br><br>**Request Body:**<br>`FeedbackCreateRequest`<br>- `content` (String, required): 피드백 내용<br>  - 유효성: `@NotBlank`, 최소 10자 이상<br>- `type` (FeedbackType, required): 피드백 유형<br>  - 값: "BUG" (버그 리포트), "SUGGESTION" (건의사항) | `FeedbackResponse`<br><br>**FeedbackResponse 구조:**<br>- `id` (String): 피드백 ID<br>- `writerId` (String): 작성자 ID (Student ID)<br>- `bojId` (String, nullable): 작성자 BOJ ID (Student를 찾을 수 없는 경우 null)<br>- `content` (String): 피드백 내용<br>- `type` (String): 피드백 유형 ("BUG", "SUGGESTION")<br>- `status` (String): 처리 상태 ("PENDING", "COMPLETED")<br>- `createdAt` (LocalDateTime): 생성 일시<br>- `updatedAt` (LocalDateTime): 수정 일시 | JWT Token |

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
  "bojId": "testuser",
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
- `DUPLICATE_NICKNAME` (400): 이미 사용 중인 닉네임
- `DUPLICATE_BOJ_ID` (409): 이미 가입된 BOJ ID
- `COMMON_RESOURCE_NOT_FOUND` (404): 요청한 자원을 찾을 수 없음
- `STUDENT_NOT_FOUND` (404): 학생을 찾을 수 없음
- `PROBLEM_NOT_FOUND` (404): 문제를 찾을 수 없음
- `RETROSPECTIVE_NOT_FOUND` (404): 회고를 찾을 수 없음
- `QUOTE_NOT_FOUND` (404): 명언을 찾을 수 없음
- `FEEDBACK_NOT_FOUND` (404): 피드백을 찾을 수 없음
- `AI_GENERATION_FAILED` (503): AI 리뷰 생성 실패
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
- 존재하지 않는 리소스 조회: 404 Not Found (해당 리소스에 맞는 에러 코드, 예: `STUDENT_NOT_FOUND`, `PROBLEM_NOT_FOUND`, `RETROSPECTIVE_NOT_FOUND`, `QUOTE_NOT_FOUND`, `FEEDBACK_NOT_FOUND`)

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

