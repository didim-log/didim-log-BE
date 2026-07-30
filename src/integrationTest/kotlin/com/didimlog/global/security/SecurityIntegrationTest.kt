package com.didimlog.global.security

import com.didimlog.DidimLogApplication
import com.didimlog.application.admin.AdminService
import com.didimlog.application.auth.AuthService
import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.auth.PasswordResetCodeGenerator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.application.student.StudentService
import com.didimlog.domain.Log
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import com.didimlog.ui.dto.AdminUserUpdateDto
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(classes = [DidimLogApplication::class])
@AutoConfigureMockMvc
@DisplayName("보안 통합 테스트")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var refreshTokenService: RefreshTokenService

    @Autowired
    private lateinit var credentialSessionCoordinator: CredentialSessionCoordinator

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var studentService: StudentService

    @Autowired
    private lateinit var adminService: AdminService

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    @Autowired
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var userToken: String
    private lateinit var adminToken: String
    private lateinit var userStudentId: String
    private lateinit var adminStudentId: String

    @BeforeEach
    fun setUp() {
        studentRepository.findByBojId(BojId("testuser"))
            .map(Student::id)
            .orElse(null)
            ?.let(refreshTokenService::revokeAllForStudent)
        studentRepository.findByBojId(BojId("admin"))
            .map(Student::id)
            .orElse(null)
            ?.let(refreshTokenService::revokeAllForStudent)
        studentRepository.deleteAll()
        logRepository.deleteAll()
        passwordResetCodeRepository.deleteAll()

        // 일반 유저 생성
        val userStudent = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            bojId = BojId("testuser"),
            password = passwordEncoder.encode(CURRENT_PASSWORD),
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val savedUserStudent = studentRepository.save(userStudent)
        userStudentId = requireNotNull(savedUserStudent.id)
        userToken = jwtTokenProvider.createToken(
            subject = "testuser",
            studentId = userStudentId,
            credentialVersion = savedUserStudent.credentialVersion,
            role = savedUserStudent.role.value
        )

        // 관리자 생성
        val adminStudent = Student(
            nickname = Nickname("adminuser"),
            provider = Provider.BOJ,
            providerId = "admin",
            bojId = BojId("admin"),
            password = "encoded",
            currentTier = Tier.GOLD,
            role = Role.ADMIN
        )
        val savedAdminStudent = studentRepository.save(adminStudent)
        adminStudentId = requireNotNull(savedAdminStudent.id)
        adminToken = jwtTokenProvider.createToken(
            subject = "admin",
            studentId = adminStudentId,
            credentialVersion = savedAdminStudent.credentialVersion,
            role = savedAdminStudent.role.value
        )
    }

    @AfterAll
    fun tearDownDatabase() {
        refreshTokenService.revokeAllForStudent(userStudentId)
        refreshTokenService.revokeAllForStudent(adminStudentId)
        mongoTemplate.db.drop()
    }

    @Test
    @DisplayName("일반 유저가 관리자 API에 접근하면 403 Forbidden이 발생한다")
    fun `일반 유저가 관리자 API 접근 시 403 Forbidden`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/admin/users")
                .header("Authorization", "Bearer $userToken")
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    @DisplayName("관리자가 관리자 API에 접근하면 정상적으로 응답한다")
    fun `관리자가 관리자 API 접근 시 정상 응답`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/admin/users")
                .header("Authorization", "Bearer $adminToken")
        )
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("토큰 없이 인증이 필요한 API에 접근하면 401 Unauthorized가 발생한다")
    fun `토큰 없이 접근 시 401 Unauthorized`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/admin/users")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType("application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    @DisplayName("인증이 필요 없는 API는 토큰 없이 접근 가능하다")
    fun `인증 불필요 API는 토큰 없이 접근 가능`() {
        // when & then - /api/v1/auth/**는 permitAll()로 설정되어 있음
        // 실제 엔드포인트가 없을 수 있으므로 401이 아닌 다른 상태 코드면 통과
        val result = mockMvc.perform(
            get("/api/v1/auth/nonexistent")
        )
        
        val status = result.andReturn().response.status
        // 401 Unauthorized가 아니면 인증이 필요 없는 것으로 간주
        assertThat(status).isNotEqualTo(401)
    }

    @Test
    @DisplayName("일반 유저가 피드백 등록 API에 접근하면 정상적으로 응답한다")
    fun `일반 유저가 피드백 등록 API 접근 시 정상 응답`() {
        // when & then
        mockMvc.perform(
            post("/api/v1/feedback")
                .header("Authorization", "Bearer $userToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "content": "버그 리포트입니다. 자세한 내용은...",
                        "type": "BUG"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
    }

    @Test
    @DisplayName("Swagger Basic 인증은 Swagger 경로에서만 사용할 수 있다")
    fun `Swagger Basic 인증 범위 제한`() {
        mockMvc.perform(
            get("/v3/api-docs")
                .with(httpBasic(SWAGGER_USERNAME, SWAGGER_PASSWORD))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/feedback")
                .with(httpBasic(SWAGGER_USERNAME, SWAGGER_PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "content": "Swagger Basic 인증은 사용자 API에 사용할 수 없습니다.",
                        "type": "BUG"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        mockMvc.perform(
            post("/api/v1/feedback")
                .header("Authorization", "Bearer $userToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "content": "Bearer 인증은 사용자 API에서 계속 동작합니다.",
                        "type": "BUG"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
    }

    @Test
    @DisplayName("BOJ ID 변경 뒤 이전 ID가 재가입되어도 기존 Access Token은 거절한다")
    fun `BOJ ID 재사용 뒤 기존 Access Token 거절`() {
        val original = studentRepository.findById(userStudentId).orElseThrow()
        studentRepository.save(original.copy(bojId = BojId("renamed_user")))
        studentRepository.save(replacementStudent(providerId = "replacement-renamed"))

        assertAccessRejected(userToken)
    }

    @Test
    @DisplayName("학생 삭제 뒤 BOJ ID가 재가입되어도 삭제된 학생의 Access Token은 거절한다")
    fun `삭제 학생 BOJ ID 재사용 뒤 기존 Access Token 거절`() {
        studentRepository.deleteById(userStudentId)
        studentRepository.save(replacementStudent(providerId = "replacement-deleted"))

        assertAccessRejected(userToken)
    }

    @Test
    @DisplayName("자격 증명 버전 변경 전 Access Token은 거절한다")
    fun `비밀번호 변경 뒤 기존 Access Token 거절`() {
        val original = studentRepository.findById(userStudentId).orElseThrow()
        assertThat(
            studentRepository.updatePasswordById(
                userStudentId,
                passwordEncoder.encode(NEW_PASSWORD),
                original.credentialVersion,
                requireNotNull(original.bojId)
            )
        ).isTrue()

        assertAccessRejected(userToken)
    }

    @Test
    @DisplayName("관리자 역할 변경은 자격 증명 버전을 올리고 기존 Access·Refresh Token을 폐기한다")
    fun `역할 변경 후 기존 Access 및 Refresh Token 거절`() {
        val student = studentRepository.findById(userStudentId).orElseThrow()
        val refreshToken = refreshTokenService.generateAndSave(student)

        adminService.updateUser(userStudentId, AdminUserUpdateDto(role = "ROLE_ADMIN"))

        val updated = studentRepository.findById(userStudentId).orElseThrow()
        assertThat(updated.role).isEqualTo(Role.ADMIN)
        assertThat(updated.credentialVersion).isEqualTo(student.credentialVersion + 1)
        assertThat(redisTemplate.opsForSet().members("refresh:student:$userStudentId").orEmpty())
            .doesNotContain(refreshToken)
        assertAccessRejected(userToken)
        assertRefreshRejected(refreshToken)
    }

    @Test
    @DisplayName("관리자가 BOJ ID를 왕복 변경해도 변경 전 Access Token은 다시 유효해지지 않는다")
    fun `BOJ ID 왕복 변경 후 기존 Access Token 거절`() {
        val original = studentRepository.findById(userStudentId).orElseThrow()

        adminService.updateUser(
            userStudentId,
            AdminUserUpdateDto(bojId = "temporary_user")
        )
        adminService.updateUser(
            userStudentId,
            AdminUserUpdateDto(bojId = requireNotNull(original.bojId).value)
        )

        val restored = studentRepository.findById(userStudentId).orElseThrow()
        assertThat(restored.bojId).isEqualTo(original.bojId)
        assertThat(restored.credentialVersion).isEqualTo(original.credentialVersion + 2)
        assertAccessRejected(userToken)
    }

    @Test
    @DisplayName("Refresh Token으로 인증이 필요한 API에 접근하면 401 Unauthorized가 발생한다")
    fun `Refresh Token으로 보호 API 접근 시 401 Unauthorized`() {
        val refreshToken = jwtTokenProvider.createRefreshToken(
            "testuser",
            userStudentId,
            credentialVersion = 0
        )

        mockMvc.perform(
            post("/api/v1/feedback")
                .header("Authorization", "Bearer $refreshToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "content": "Refresh Token은 인증에 사용할 수 없습니다.",
                        "type": "BUG"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType("application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    @DisplayName("Refresh Token을 Authorization 헤더로 전달해 토큰을 갱신할 수 있다")
    fun `Refresh Token 헤더 갱신 성공`() {
        val student = studentRepository.findById(userStudentId).orElseThrow()
        val refreshToken = refreshTokenService.generateAndSave(student)

        try {
            mockMvc.perform(
                post("/api/v1/auth/refresh")
                    .header("Authorization", "Bearer $refreshToken")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.token").isNotEmpty)
                .andExpect(jsonPath("$.refreshToken").isNotEmpty)
        } finally {
            refreshTokenService.revokeAllForStudent(userStudentId)
        }
    }

    @Test
    @DisplayName("비밀번호 재설정은 사용자의 모든 기존 Refresh Token을 폐기한다")
    fun `비밀번호 재설정 후 기존 Refresh Token 폐기`() {
        val student = studentRepository.findByBojId(BojId("testuser")).orElseThrow()
        val firstToken = refreshTokenService.generateAndSave(student)
        val secondToken = refreshTokenService.generateAndSave(student)
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = "RESET001",
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = java.time.LocalDateTime.now().plusMinutes(30)
            )
        )

        authService.resetPassword("RESET001", NEW_PASSWORD)

        assertRefreshRejected(firstToken)
        assertRefreshRejected(secondToken)
        val updated = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(passwordEncoder.matches(NEW_PASSWORD, updated.password)).isTrue()
    }

    @Test
    @DisplayName("인증된 사용자의 비밀번호 변경은 모든 기존 Refresh Token을 폐기한다")
    fun `프로필 비밀번호 변경 후 기존 Refresh Token 폐기`() {
        val student = studentRepository.findById(userStudentId).orElseThrow()
        val firstToken = refreshTokenService.generateAndSave(student)
        val secondToken = refreshTokenService.generateAndSave(student)

        studentService.updateProfile(
            studentId = userStudentId,
            nickname = null,
            currentPassword = CURRENT_PASSWORD,
            newPassword = NEW_PASSWORD
        )

        assertRefreshRejected(firstToken)
        assertRefreshRejected(secondToken)
    }

    @Test
    @DisplayName("Redis에 남은 구 버전 Refresh Token도 비밀번호 버전이 바뀌면 거절한다")
    fun `자격 증명 버전 변경 후 Redis 잔존 Refresh Token 거절`() {
        val student = studentRepository.findByBojId(BojId("testuser")).orElseThrow()
        val refreshToken = refreshTokenService.generateAndSave(student)

        assertThat(
            studentRepository.updatePasswordById(
                requireNotNull(student.id),
                passwordEncoder.encode(NEW_PASSWORD),
                student.credentialVersion,
                requireNotNull(student.bojId)
            )
        ).isTrue()
        assertThat(redisTemplate.opsForSet().isMember("refresh:student:$userStudentId", refreshToken))
            .isTrue()
        assertThat(
            studentRepository.updatePasswordById(
                requireNotNull(student.id),
                passwordEncoder.encode("StalePassword123!"),
                student.credentialVersion,
                requireNotNull(student.bojId)
            )
        ).isFalse()

        assertRefreshRejected(refreshToken)
        val updatedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(updatedStudent.credentialVersion).isEqualTo(student.credentialVersion + 1)
        assertThat(passwordEncoder.matches(NEW_PASSWORD, updatedStudent.password)).isTrue()
    }

    @Test
    @DisplayName("같은 학생의 자격 증명 작업이 진행 중이면 두 번째 요청을 거절한다")
    fun `학생별 Redis 잠금 경합 시 두 번째 요청 거절`() {
        val student = studentRepository.findByBojId(BojId("testuser")).orElseThrow()
        val studentId = requireNotNull(student.id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstRequest = executor.submit {
                credentialSessionCoordinator.execute(studentId) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }

            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            val exception = assertThrows<BusinessException> {
                credentialSessionCoordinator.execute(studentId) {
                    error("잠금 경합 중에는 실행되면 안 됩니다.")
                }
            }
            assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)

            release.countDown()
            firstRequest.get(10, TimeUnit.SECONDS)
            assertThat(
                credentialSessionCoordinator.execute(studentId) {
                    "released"
                }
            ).isEqualTo("released")
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("비밀번호 재설정 중 지연된 구 비밀번호 로그인은 Refresh Token을 만들지 못한다")
    fun `비밀번호 재설정 후 지연 로그인 세션 발급 차단`() {
        val student = studentRepository.findByBojId(BojId("testuser")).orElseThrow()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = "RESET-RACE",
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = java.time.LocalDateTime.now().plusMinutes(30)
            )
        )
        val blockingSolvedAcClient = BlockingSolvedAcClient()
        val delayedAuthService = AuthService(
            solvedAcClient = blockingSolvedAcClient,
            studentRepository = studentRepository,
            jwtTokenProvider = jwtTokenProvider,
            passwordEncoder = passwordEncoder,
            emailService = mockk<EmailService>(relaxed = true),
            passwordResetCodeRepository = passwordResetCodeRepository,
            passwordResetCodeGenerator = mockk<PasswordResetCodeGenerator>(relaxed = true),
            refreshTokenService = refreshTokenService,
            bojOwnershipVerificationService = mockk<BojOwnershipVerificationService>(relaxed = true),
            credentialSessionCoordinator = credentialSessionCoordinator
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val loginFuture = executor.submit<AuthService.AuthResult> {
                delayedAuthService.login("testuser", CURRENT_PASSWORD)
            }

            assertThat(blockingSolvedAcClient.entered.await(10, TimeUnit.SECONDS)).isTrue()
            authService.resetPassword("RESET-RACE", NEW_PASSWORD)
            blockingSolvedAcClient.release.countDown()

            val failure = assertThrows<ExecutionException> {
                loginFuture.get(10, TimeUnit.SECONDS)
            }
            assertThat(failure.cause).isInstanceOf(BusinessException::class.java)
            assertThat((failure.cause as BusinessException).errorCode)
                .isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
            assertThat(redisTemplate.opsForSet().members("refresh:student:$userStudentId").orEmpty())
                .isEmpty()
        } finally {
            blockingSolvedAcClient.release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun assertRefreshRejected(refreshToken: String) {
        val exception = assertThrows<com.didimlog.global.exception.BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }
        assertThat(exception.errorCode)
            .isEqualTo(com.didimlog.global.exception.ErrorCode.COMMON_INVALID_INPUT)
    }

    private fun assertAccessRejected(accessToken: String) {
        mockMvc.perform(
            post("/api/v1/feedback")
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "content": "기존 Access Token은 사용할 수 없습니다.",
                        "type": "BUG"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    private fun replacementStudent(providerId: String): Student {
        return Student(
            nickname = Nickname("newtester"),
            provider = Provider.BOJ,
            providerId = providerId,
            bojId = BojId("testuser"),
            password = passwordEncoder.encode(CURRENT_PASSWORD),
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    @Test
    @DisplayName("다른 사용자의 로그에는 AI 리뷰 피드백을 제출할 수 없다")
    fun `타인 로그 피드백 제출 시 403 Forbidden`() {
        val log = logRepository.save(
            Log(
                title = LogTitle("소유권 검증"),
                content = LogContent("testuser의 로그"),
                code = LogCode("fun main() = Unit"),
                studentId = userStudentId,
                bojId = BojId("testuser")
            )
        )

        mockMvc.perform(
            post("/api/v1/logs/${requireNotNull(log.id)}/feedback")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"LIKE"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        val persistedLog = logRepository.findById(requireNotNull(log.id)).orElseThrow()
        assertThat(persistedLog.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.NONE)
    }

    @Test
    @DisplayName("로그 소유자는 AI 리뷰 피드백을 제출할 수 있다")
    fun `본인 로그 피드백 제출 성공`() {
        val log = logRepository.save(
            Log(
                title = LogTitle("소유권 검증"),
                content = LogContent("testuser의 로그"),
                code = LogCode("fun main() = Unit"),
                studentId = userStudentId,
                bojId = BojId("testuser")
            )
        )

        mockMvc.perform(
            post("/api/v1/logs/${requireNotNull(log.id)}/feedback")
                .header("Authorization", "Bearer $userToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"DISLIKE","reason":"INACCURATE"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("피드백이 제출되었습니다."))

        val persistedLog = logRepository.findById(requireNotNull(log.id)).orElseThrow()
        assertThat(persistedLog.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.DISLIKE)
        assertThat(persistedLog.aiFeedbackReason).isEqualTo("INACCURATE")
    }

    private class BlockingSolvedAcClient : SolvedAcClient {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
            error("이 테스트에서는 문제 조회를 사용하지 않는다.")
        }

        override fun fetchUser(bojId: BojId): SolvedAcUserResponse {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return SolvedAcUserResponse(
                handle = bojId.value,
                rating = 0,
                tier = 0
            )
        }
    }

    companion object {
        private const val CURRENT_PASSWORD = "CurrentPassword123!"
        private const val NEW_PASSWORD = "NewPassword123!"
        private const val SWAGGER_USERNAME = "test-swagger"
        private const val SWAGGER_PASSWORD = "test-swagger-password"
        private val testDatabaseName =
            "didimlog-security-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$testDatabaseName"
            }
        }
    }
}
