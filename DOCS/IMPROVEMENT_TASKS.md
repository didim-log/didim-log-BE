# 프론트엔드 개선 작업 보고서

**작성일**: 2025-01-02  
**최종 수정일**: 2025-01-02  
**작성자**: AI Assistant  
**목적**: 사용자 요청사항에 따른 프론트엔드 및 백엔드 개선 작업 계획

---

## 개요

사용자 피드백을 바탕으로 다음과 같은 개선 작업이 필요합니다:

1. **피드백 관리 페이지 개선**
2. **회고 목록 표시 및 동기화 개선**
3. **대시보드 명언 표시 개선**
4. **시간 표시 개선 (UTC → KST)**
5. **통계 페이지 개선 (차트 크기, 알고리즘 통계)**
6. **회고 마크다운 Footer 추가**
7. **문제 풀이 페이지 개선 (지연 로딩, 예제 복사, 타이머 개선, 풀이 시간 연결, 문제 설명 로딩 통일)**
8. **대시보드 개선 (최근 풀이 활동 정보 추가, 오늘 푼 문제를 회고 기준으로 변경)**
9. **문제 검색 기능 추가**
10. **관리자 대시보드 통계 개선 (필터, 그래프, 검색)**
11. **문제 이미지 표시 문제 처리**
12. **회원가입 이메일 필수 처리 (신규)**

**⚠️ 중요: 백엔드 작업 시 반드시 준수해야 할 사항**
- API 변경 시 `DOCS/API_SPECIFICATION.md` 최신화 필수
- 클린코드 원칙 준수 (`DOCS/PR_GUIDE.md`)
- 테스트 커버리지 확보를 위한 테스트 코드 작성 필수
- 작업 완료 후 파일 단위로 커밋 컨벤션 준수하여 커밋 (`DOCS/COMMIT_CONVENTION.md`)
- 배포 전 모든 항목 완료 확인 필수

자세한 내용은 **"백엔드 작업 가이드라인"** 섹션을 참고하세요.

---

## 12. 회원가입 이메일 필수 처리 (신규)

### 12.1 현재 상태

**파일**: 
- `src/main/kotlin/com/didimlog/ui/dto/AuthRequest.kt` - BOJ 회원가입 요청 DTO
- `src/main/kotlin/com/didimlog/ui/dto/SuperAdminRequest.kt` - 슈퍼 관리자 계정 생성 요청 DTO
- `src/main/kotlin/com/didimlog/ui/dto/SignupFinalizeRequest.kt` - 소셜 로그인 가입 마무리 요청 DTO (이미 이메일 필수)
- `src/main/kotlin/com/didimlog/application/auth/AuthService.kt` - 인증 서비스

**문제점:**
- BOJ 자체 로그인 회원가입(`/api/v1/auth/signup`)에서 이메일 필드가 없음
- 슈퍼 관리자 계정 생성(`/api/v1/auth/super-admin`)에서 이메일 필드가 없음
- 소셜 로그인 가입 마무리(`/api/v1/auth/signup/finalize`)는 이미 이메일 필수 (`@NotBlank`)
- 아이디/비밀번호 찾기 기능에서 이메일이 필요하므로 모든 회원가입 방식에서 이메일 필수 입력 필요

### 12.2 개선 사항

#### 12.2.1 모든 회원가입 방식에서 이메일 필수 처리

**작업 내용:**
1. ✅ `AuthRequest`에 `email` 필드 추가 (필수, `@Email` 검증)
2. ✅ `SuperAdminRequest`에 `email` 필드 추가 (필수, `@Email` 검증)
3. ✅ `AuthService.signup()` 메서드에 `email` 파라미터 추가 및 이메일 중복 체크
4. ✅ `AuthService.createSuperAdmin()` 메서드에 `email` 파라미터 추가 및 이메일 중복 체크
5. ✅ `AuthService.finalizeSignup()` 메서드의 `email` 파라미터를 nullable에서 non-nullable로 변경 및 이메일 중복 체크
6. ✅ `AuthController` 수정하여 이메일 파라미터 전달
7. ⚠️ 테스트 코드 작성 및 업데이트 (진행 중)
8. ⚠️ API 명세서 업데이트 (`DOCS/API_SPECIFICATION.md`)

**예상 코드 변경:**
```kotlin
// src/main/kotlin/com/didimlog/ui/dto/AuthRequest.kt
data class AuthRequest(
    @field:NotBlank(message = "BOJ ID는 필수입니다.")
    val bojId: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String,

    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    val email: String
)

// src/main/kotlin/com/didimlog/application/auth/AuthService.kt
fun signup(bojId: String, password: String, email: String): AuthResult {
    // ... 기존 로직
    
    // 이메일 중복 체크 추가
    val existingEmailStudent = studentRepository.findByEmail(email)
    if (existingEmailStudent.isPresent) {
        throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다. email=$email")
    }
    
    val student = Student(
        // ... 기존 필드들
        email = email,  // 이메일 필드 추가
        // ...
    )
}
```

**백엔드 작업 상태:**
- ✅ AuthRequest에 email 필드 추가
- ✅ SuperAdminRequest에 email 필드 추가
- ✅ AuthService.signup() 이메일 처리 추가
- ✅ AuthService.createSuperAdmin() 이메일 처리 추가
- ✅ AuthService.finalizeSignup() 이메일 필수 처리 및 중복 체크 추가
- ✅ AuthController 수정
- ⚠️ 테스트 코드 작성 및 업데이트 (필요)
- ⚠️ API 명세서 업데이트 (필요)

**프론트엔드 작업:**
- BOJ 회원가입 페이지에 이메일 입력 필드 추가
- 소셜 로그인 가입 마무리 페이지에서 이메일이 제공되지 않은 경우 사용자가 직접 입력할 수 있도록 UI 구성

---

## 백엔드 작업 가이드라인

### 작업 전 필수 확인 사항

모든 백엔드 작업은 배포 전에 다음 사항들을 반드시 완료해야 합니다:

#### 1. API 명세서 최신화 (`DOCS/API_SPECIFICATION.md`)

**작업 내용:**
- 백엔드 API 변경 시 반드시 `DOCS/API_SPECIFICATION.md` 파일을 최신화해야 합니다
- 새로운 엔드포인트 추가 시:
  - Method, URI, 기능 설명 추가
  - Request/Response 구조 상세히 문서화
  - 예시 요청/응답 추가
  - 에러 응답 예시 추가 (필요한 경우)
- 기존 API 수정 시:
  - 변경된 Request/Response 필드 명시
  - Breaking Change가 있는 경우 명확히 표시
  - 예시 요청/응답 업데이트

**예시:**
```markdown
| POST | `/api/v1/auth/signup` | BOJ ID, 비밀번호, 이메일을 입력받아 회원가입을 진행합니다. | **Request Body:**<br>`AuthRequest`<br>- `bojId` (String, required): BOJ ID<br>- `password` (String, required): 비밀번호 (8자 이상)<br>- `email` (String, required): 이메일 주소<br>  - 유효성: `@NotBlank`, `@Email` | `AuthResponse` | None |
```

#### 2. 클린코드 원칙 준수 (`DOCS/PR_GUIDE.md`)

**엄격히 준수해야 할 규칙:**

1. **Indent Depth는 1까지만 허용**
   - `if`, `for`, `while` 등이 중첩되면 메서드로 분리

2. **`else` 예약어 사용 금지**
   - Early Return 패턴 사용

3. **모든 원시값과 문자열 포장 (Wrapping)**
   - 예: `int age` -> `class Age`

4. **일급 컬렉션 사용**
   - `List<Member>` 등 컬렉션을 필드로 가지는 별도 클래스 생성

5. **3개 이상의 인스턴스 변수를 가진 클래스 사용 금지**

6. **Getter/Setter 사용 지양**
   - 객체에서 데이터를 꺼내지 말고, 객체에 메시지를 보내라 (DTO는 예외)

7. **한 메서드는 오직 한 가지 일만 수행**

**예시:**
```kotlin
// Bad
fun processUser(user: User): String {
    if (user.age > 18) {
        if (user.email.isNotEmpty()) {
            return "Adult with email"
        } else {
            return "Adult without email"
        }
    } else {
        return "Minor"
    }
}

// Good
fun processUser(user: User): String {
    if (user.isMinor()) {
        return "Minor"
    }
    
    if (user.hasEmail()) {
        return "Adult with email"
    }
    
    return "Adult without email"
}

private fun User.isMinor(): Boolean = age < 18
private fun User.hasEmail(): Boolean = email.isNotEmpty()
```

#### 3. 테스트 커버리지 및 안정성 테스트 코드 작성

**작업 내용:**
- 모든 새로운 기능에 대해 단위 테스트 작성 (JUnit5)
- 통합 테스트 작성 (필요한 경우)
- 테스트 커버리지 목표: 주요 비즈니스 로직 80% 이상
- 엣지 케이스 및 에러 케이스 테스트 포함

**테스트 작성 원칙:**
- `@Test` 어노테이션 사용 (JUnit5)
- Given-When-Then 패턴 사용
- 테스트 메서드명은 한글로 작성 가능 (가독성 향상)
- Mock 객체 사용 시 MockK 활용

**예시:**
```kotlin
@Test
fun `문제 상세 정보가 DB에 없으면 크롤링하여 저장한다`() {
    // Given
    val problemId = 1000L
    val existingProblem = Problem(id = ProblemId("1000"), title = "Test", ...)
    val crawledDetails = CrawledProblemDetails(
        descriptionHtml = "<p>Test description</p>",
        ...
    )
    
    every { problemRepository.findById(problemId.toString()) } returns Optional.of(existingProblem)
    every { bojCrawler.crawlProblemDetails(problemId.toString()) } returns crawledDetails
    every { problemRepository.save(any()) } returnsArgument 0
    
    // When
    val result = problemService.getProblemDetail(problemId)
    
    // Then
    assertThat(result.descriptionHtml).isEqualTo(crawledDetails.descriptionHtml)
    verify(exactly = 1) { problemRepository.save(any()) }
}
```

#### 4. 커밋 컨벤션 준수 (`DOCS/COMMIT_CONVENTION.md`)

**커밋 메시지 형식:**
```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type (필수):**
- `feat`: 새로운 기능 추가
- `fix`: 버그 수정
- `docs`: 문서 수정
- `style`: 코드 포맷팅 (로직 변경 없음)
- `refactor`: 코드 리팩토링 (기능 변경 없음)
- `test`: 테스트 코드 추가/수정
- `chore`: 빌드 설정, 패키지 매니저 설정 등

**Subject (필수):**
- 50자 이내로 간결하게 작성
- 명령문으로 시작 (예: "수정한다" X -> "수정" O)
- 끝에 마침표(.) 없음

**작업 완료 후:**
- 파일 단위로 변경사항 커밋
- 각 커밋은 논리적으로 분리된 작업 단위로 구성
- 하나의 커밋에 여러 파일이 포함되어도 논리적으로 관련된 변경사항만 포함

**예시:**
```bash
# 좋은 예
git commit -m "feat(problem): 문제 상세 조회 시 DB에 없으면 크롤링 후 저장"

# 나쁜 예
git commit -m "fix"
git commit -m "문제 수정"
git commit -m "feat: 문제 API 수정 및 크롤링 로직 추가 그리고 테스트 코드 작성"
```

### 작업 순서

1. **기능 구현**
   - 클린코드 원칙 준수하며 구현
   - API 명세서에 변경사항 반영 (동시에 또는 구현 후)

2. **테스트 코드 작성**
   - 단위 테스트 작성
   - 통합 테스트 작성 (필요한 경우)
   - 테스트 실행 및 통과 확인

3. **코드 리뷰 (필요한 경우)**
   - PR 생성 전 로컬에서 코드 검토
   - 클린코드 원칙 준수 여부 확인

4. **커밋**
   - 파일 단위로 논리적으로 분리하여 커밋
   - 커밋 컨벤션 준수

5. **배포 전 최종 확인**
   - 모든 테스트 통과 확인
   - API 명세서 최신화 확인
   - 커밋 메시지 컨벤션 준수 확인

### 주의사항

- **배포 전 모든 항목 완료 필수**: 테스트 코드 작성, API 명세서 최신화, 커밋 컨벤션 준수
- **프론트엔드 작업 의존성**: 프론트엔드 작업은 백엔드 배포 후 진행 가능
- **Breaking Change**: API 변경 시 프론트엔드 영향도 고려
- **에러 처리**: 모든 에러 케이스에 대해 적절한 에러 코드 및 메시지 반환

---

## 참고 파일

**프론트엔드:**
- `src/pages/AdminPage.tsx` - 피드백 관리 페이지
- `src/pages/MyPage.tsx` - 회고 목록 페이지
- `src/components/retrospective/RetrospectiveCard.tsx` - 회고 카드 컴포넌트
- `src/pages/RetrospectiveWritePage.tsx` - 회고 작성 페이지
- `src/pages/DashboardPage.tsx` - 대시보드 페이지
- `src/pages/ProblemPage.tsx` - 문제 풀이 페이지
- `src/pages/RecommendedProblemsPage.tsx` - 추천 문제 페이지
- `src/components/problem/Timer.tsx` - 타이머 컴포넌트
- `src/types/api/dtos.ts` - API 타입 정의
- `src/apis/adminApi.ts` - 관리자 API 함수

**백엔드:**
- `DOCS/API_SPECIFICATION.md` - API 명세서 (백엔드 API 변경 시 반드시 최신화)
- `DOCS/PR_GUIDE.md` - 클린코드 원칙 및 PR 가이드라인
- `DOCS/COMMIT_CONVENTION.md` - Git 커밋 컨벤션
