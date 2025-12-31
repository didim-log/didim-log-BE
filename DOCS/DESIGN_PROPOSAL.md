# 엔티티 설계 및 테스트 명세 제안서

## 📋 설계 원칙 (PR_GUIDE.md 준수)

1. **Indent Depth 1**: 모든 중첩은 메서드로 분리
2. **else 키워드 금지**: Early Return 패턴 적용
3. **Getter/Setter 지양**: 객체에 메시지 보내기 (Tell, Don't Ask)
4. **원시값 포장**: 가능한 경우 Value Object로 포장
5. **단일 책임**: 한 메서드는 한 가지 일만 수행

---

## 1. Student 엔티티 확장 설계

### 1.1 추가될 메서드

```kotlin
/**
 * 주로 사용하는 프로그래밍 언어를 업데이트한다.
 *
 * @param language 새로운 언어
 * @return 언어가 업데이트된 새로운 Student 인스턴스
 */
fun updatePrimaryLanguage(language: PrimaryLanguage): Student {
    return copy(primaryLanguage = language)
}
```

**설계 이유:**
- Getter/Setter 지양: `student.primaryLanguage = language` 대신 `student.updatePrimaryLanguage(language)` 사용
- 불변 객체 유지: `copy()`를 통해 새로운 인스턴스 반환

---

## 2. Retrospective 엔티티 설계 개선

### 2.1 현재 문제점

1. **보안 취약점**: 소유권 검증 로직이 도메인 객체에 없음
2. **서비스 레이어 의존**: `RetrospectiveService`에서 `studentId` 비교를 직접 수행 (Tell, Don't Ask 위반)

### 2.2 개선된 설계

#### 추가될 메서드

```kotlin
/**
 * 회고의 소유자인지 확인한다.
 * 객체 지향적 설계: 데이터를 꺼내지 말고 객체에 메시지를 보낸다.
 *
 * @param student 확인할 학생
 * @return 소유자이면 true, 그렇지 않으면 false
 */
fun isOwner(student: Student): Boolean {
    val studentId = student.id
    if (studentId == null) {
        return false
    }
    return this.studentId == studentId
}

/**
 * 회고의 소유자인지 검증한다.
 * 소유자가 아니면 예외를 발생시킨다.
 *
 * @param student 확인할 학생
 * @throws IllegalArgumentException 소유자가 아닌 경우
 */
fun validateOwner(student: Student) {
    if (!isOwner(student)) {
        throw IllegalArgumentException("회고 소유자가 아닙니다. studentId=${student.id}")
    }
}
```

**설계 이유:**
- **Tell, Don't Ask**: `retrospective.studentId == student.id` 대신 `retrospective.isOwner(student)` 사용
- **Early Return**: `isOwner()`에서 null 체크 후 early return
- **단일 책임**: `isOwner()`는 확인만, `validateOwner()`는 검증만 담당

### 2.3 필드 구조 (변경 없음)

현재 Retrospective 엔티티의 필드 구조는 적절합니다:
- `studentId: String` (Student의 DB ID)
- `problemId: String`
- `content: String`
- `summary: String?`
- `solutionResult: ProblemResult?`
- `solvedCategory: String?`
- 기타 메타데이터 필드들

**원시값 포장 고려사항:**
- `studentId`, `problemId`를 Value Object로 포장할 수 있으나, 기존 코드베이스와의 호환성을 위해 현재는 String 유지
- 추후 리팩토링 시 고려 가능

---

## 3. 테스트 코드 명세

### 3.1 Student 테스트 (`StudentTest.kt`)

#### 3.1.1 PrimaryLanguage 관련 테스트

```kotlin
@Test
@DisplayName("updatePrimaryLanguage는 새로운 언어로 Student를 업데이트한다")
fun `primaryLanguage 업데이트 성공`() {
    // given
    val student = createStudent(primaryLanguage = null)
    
    // when
    val updated = student.updatePrimaryLanguage(PrimaryLanguage.JAVA)
    
    // then
    assertThat(updated.primaryLanguage).isEqualTo(PrimaryLanguage.JAVA)
}

@Test
@DisplayName("updatePrimaryLanguage는 기존 언어를 새로운 언어로 변경할 수 있다")
fun `primaryLanguage 변경 성공`() {
    // given
    val student = createStudent(primaryLanguage = PrimaryLanguage.PYTHON)
    
    // when
    val updated = student.updatePrimaryLanguage(PrimaryLanguage.KOTLIN)
    
    // then
    assertThat(updated.primaryLanguage).isEqualTo(PrimaryLanguage.KOTLIN)
    assertThat(student.primaryLanguage).isEqualTo(PrimaryLanguage.PYTHON) // 원본 불변 확인
}
```

### 3.2 Retrospective 테스트 (`RetrospectiveTest.kt`)

#### 3.2.1 소유권 검증 테스트

```kotlin
@Test
@DisplayName("isOwner는 회고 소유자일 때 true를 반환한다")
fun `소유자인 경우 true 반환`() {
    // given
    val ownerId = "owner-123"
    val student = createStudent(id = ownerId)
    val retrospective = createRetrospective(studentId = ownerId)
    
    // when
    val result = retrospective.isOwner(student)
    
    // then
    assertThat(result).isTrue()
}

@Test
@DisplayName("isOwner는 회고 소유자가 아닐 때 false를 반환한다")
fun `소유자가 아닌 경우 false 반환`() {
    // given
    val ownerId = "owner-123"
    val otherId = "other-456"
    val otherStudent = createStudent(id = otherId)
    val retrospective = createRetrospective(studentId = ownerId)
    
    // when
    val result = retrospective.isOwner(otherStudent)
    
    // then
    assertThat(result).isFalse()
}

@Test
@DisplayName("isOwner는 Student의 id가 null일 때 false를 반환한다")
fun `Student id가 null인 경우 false 반환`() {
    // given
    val student = createStudent(id = null)
    val retrospective = createRetrospective(studentId = "owner-123")
    
    // when
    val result = retrospective.isOwner(student)
    
    // then
    assertThat(result).isFalse()
}

@Test
@DisplayName("validateOwner는 소유자일 때 예외를 발생시키지 않는다")
fun `소유자 검증 성공`() {
    // given
    val ownerId = "owner-123"
    val student = createStudent(id = ownerId)
    val retrospective = createRetrospective(studentId = ownerId)
    
    // when & then
    assertThatCode {
        retrospective.validateOwner(student)
    }.doesNotThrowAnyException()
}

@Test
@DisplayName("validateOwner는 소유자가 아닐 때 예외를 발생시킨다")
fun `소유자 검증 실패 시 예외 발생`() {
    // given
    val ownerId = "owner-123"
    val otherId = "other-456"
    val otherStudent = createStudent(id = otherId)
    val retrospective = createRetrospective(studentId = ownerId)
    
    // when & then
    assertThatThrownBy {
        retrospective.validateOwner(otherStudent)
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("회고 소유자가 아닙니다")
}
```

### 3.3 RetrospectiveService 테스트 (`RetrospectiveServiceTest.kt`)

#### 3.3.1 보안 검증 테스트

```kotlin
@Test
@DisplayName("writeRetrospective는 다른 사용자의 studentId로 회고를 작성할 수 없다")
fun `다른 사용자 ID로 회고 작성 시도 시 예외 발생`() {
    // given
    val ownerId = "owner-123"
    val attackerId = "attacker-456"
    val ownerStudent = createStudent(id = ownerId)
    val attackerStudent = createStudent(id = attackerId)
    
    every { studentRepository.findById(ownerId) } returns Optional.of(ownerStudent)
    every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)
    every { problemRepository.findById(any()) } returns Optional.of(createProblem())
    
    // when & then
    assertThatThrownBy {
        retrospectiveService.writeRetrospective(
            studentId = attackerId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다.",
            summary = "요약"
        )
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("회고 소유자가 아닙니다")
}

@Test
@DisplayName("deleteRetrospective는 다른 사용자의 회고를 삭제할 수 없다")
fun `다른 사용자 회고 삭제 시도 시 예외 발생`() {
    // given
    val ownerId = "owner-123"
    val attackerId = "attacker-456"
    val ownerStudent = createStudent(id = ownerId)
    val attackerStudent = createStudent(id = attackerId)
    val retrospective = createRetrospective(studentId = ownerId, id = "retro-1")
    
    every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
    every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)
    
    // when & then
    assertThatThrownBy {
        // RetrospectiveService.deleteRetrospective는 studentId를 받아야 함 (메서드 시그니처 변경 필요)
        retrospectiveService.deleteRetrospective(
            retrospectiveId = "retro-1",
            studentId = attackerId
        )
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("회고 소유자가 아닙니다")
}
```

### 3.4 RetrospectiveController 테스트 (`RetrospectiveControllerTest.kt`)

#### 3.4.1 보안 통합 테스트

```kotlin
@Test
@DisplayName("POST /api/v1/retrospectives는 쿼리 파라미터의 studentId와 JWT 토큰의 사용자가 다르면 403을 반환한다")
fun `쿼리 파라미터와 JWT 토큰 불일치 시 403 Forbidden`() {
    // given
    val tokenOwnerId = "token-owner-123"
    val queryParamStudentId = "query-param-456"
    val jwtToken = generateJwtToken(bojId = "tokenOwnerBojId")
    
    // when & then
    mockMvc.perform(
        post("/api/v1/retrospectives")
            .param("studentId", queryParamStudentId)
            .param("problemId", "problem-1")
            .header("Authorization", "Bearer $jwtToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "content": "충분히 긴 회고 내용입니다."
                }
            """.trimIndent())
    )
        .andExpect(status().isForbidden)
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
}
```

### 3.5 AuthService 테스트 (`AuthServiceTest.kt`)

#### 3.5.1 JWT 토큰 생성 검증

```kotlin
@Test
@DisplayName("login은 JWT 토큰의 sub 클레임에 bojId가 올바르게 들어간다")
fun `JWT 토큰 sub 클레임에 bojId 포함 확인`() {
    // given
    val bojId = "test-user"
    val password = "securePassword123"
    val student = createStudent(bojId = BojId(bojId))
    
    every { studentRepository.findByBojId(any()) } returns Optional.of(student)
    every { passwordEncoder.matches(any(), any()) } returns true
    every { solvedAcClient.fetchUser(any()) } returns createUserResponse()
    every { jwtTokenProvider.createToken(any(), any()) } answers { 
        // 첫 번째 인자가 bojId인지 확인
        val subject = firstArg<String>()
        assertThat(subject).isEqualTo(bojId)
        "mock-token"
    }
    
    // when
    authService.login(bojId, password)
    
    // then: verify를 통해 검증됨 (위에서 assertThat 사용)
}

@Test
@DisplayName("login은 민감 정보(비밀번호)를 로그에 출력하지 않는다")
fun `비밀번호 로그 출력 방지 확인`() {
    // given
    val bojId = "test-user"
    val password = "secretPassword123"
    val student = createStudent(bojId = BojId(bojId))
    
    every { studentRepository.findByBojId(any()) } returns Optional.of(student)
    every { passwordEncoder.matches(any(), any()) } returns false // 비밀번호 불일치
    every { log.warn(any<String>(), any()) } answers {
        val message = firstArg<String>()
        // 로그 메시지에 비밀번호가 포함되어 있지 않은지 확인
        assertThat(message).doesNotContain(password)
    }
    
    // when & then
    assertThatThrownBy {
        authService.login(bojId, password)
    }.isInstanceOf(BusinessException::class.java)
}
```

---

## 4. RetrospectiveService 리팩토링 계획

### 4.1 메서드 시그니처 변경

현재 `deleteRetrospective(retrospectiveId: String)` 메서드를 다음과 같이 변경:

```kotlin
/**
 * 회고를 삭제한다.
 * 소유권 검증을 수행한다.
 *
 * @param retrospectiveId 회고 ID
 * @param studentId 삭제를 시도하는 학생 ID (보안 검증용)
 * @throws IllegalArgumentException 회고를 찾을 수 없거나 소유자가 아닌 경우
 */
@Transactional
fun deleteRetrospective(retrospectiveId: String, studentId: String): Retrospective {
    val retrospective = getRetrospective(retrospectiveId)
    val student = getStudent(studentId)
    
    // 객체 지향적 검증: Tell, Don't Ask
    retrospective.validateOwner(student)
    
    retrospectiveRepository.delete(retrospective)
    return retrospective
}
```

**설계 이유:**
- **Tell, Don't Ask**: `retrospective.validateOwner(student)` 사용
- **Early Return**: `validateOwner()` 내부에서 예외 발생 시 early return

---

## 5. RetrospectiveController 보안 강화 계획

### 5.1 현재 문제점

API 명세서에 따르면 `POST /api/v1/retrospectives?studentId=xxx` 형태로 `studentId`를 쿼리 파라미터로 받지만, 현재 구현은 JWT 토큰에서만 추출합니다.

### 5.2 개선된 구현

```kotlin
@PostMapping
fun writeRetrospective(
    authentication: Authentication,
    @RequestParam studentId: String, // API 명세서에 맞춰 쿼리 파라미터로 받음
    @RequestParam problemId: String,
    @RequestBody @Valid request: RetrospectiveRequest
): ResponseEntity<RetrospectiveResponse> {
    // 1. JWT 토큰에서 현재 사용자 정보 추출
    val bojId = authentication.name
    val currentStudent = getStudentByBojId(bojId)
    
    // 2. 쿼리 파라미터의 studentId와 JWT 토큰의 사용자 일치 여부 검증
    if (currentStudent.id != studentId) {
        throw AccessDeniedException("회고를 작성할 권한이 없습니다.")
    }
    
    // 3. 회고 작성 (RetrospectiveService에서 추가 소유권 검증 수행)
    val retrospective = retrospectiveService.writeRetrospective(
        studentId = studentId,
        problemId = problemId,
        content = request.content,
        summary = request.summary,
        solutionResult = request.resultType,
        solvedCategory = request.solvedCategory
    )
    
    return ResponseEntity.ok(RetrospectiveResponse.from(retrospective))
}
```

**보안 계층:**
1. **Controller 레벨**: 쿼리 파라미터와 JWT 토큰 일치 검증
2. **Service 레벨**: Retrospective 엔티티의 `validateOwner()` 메서드로 추가 검증 (Defense in Depth)

---

## 6. 구현 순서

1. ✅ `PrimaryLanguage` Enum 생성 (완료)
2. ✅ `Student` 엔티티에 `primaryLanguage` 필드 추가 (완료)
3. `Student.updatePrimaryLanguage()` 메서드 추가
4. `Student` 테스트 코드 작성
5. `Retrospective.isOwner()` 및 `validateOwner()` 메서드 추가
6. `Retrospective` 테스트 코드 작성
7. `RetrospectiveService` 리팩토링 (소유권 검증 추가)
8. `RetrospectiveService` 테스트 코드 작성/수정
9. `RetrospectiveController` 보안 강화
10. `RetrospectiveController` 테스트 코드 작성
11. `AuthService` 보안 점검 및 테스트
12. `UpdateProfileRequest` DTO 수정 (primaryLanguage 추가)
13. `StudentService.updateProfile()` 메서드 수정
14. API 명세서 최신화

---

## 📝 검토 요청사항

1. **StudentId Value Object 포장**: 현재는 String으로 유지하는 것이 적절한가요, 아니면 Value Object로 포장할까요?
2. **Retrospective 필드**: `aiAnalysisData` 필드가 필요한가요? (JSON String vs 구조화된 객체)
3. **보안 예외 타입**: `IllegalArgumentException` 대신 `AccessDeniedException` (Spring Security) 사용할까요?

위 설계에 대한 검토와 승인을 부탁드립니다. 승인해 주시면 실제 구현을 진행하겠습니다.
