package com.didimlog.global.security

import com.didimlog.DidimLogApplication
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Log
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

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
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        studentRepository.deleteAll()
        logRepository.deleteAll()

        // 일반 유저 생성
        val userStudent = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            bojId = BojId("testuser"),
            password = "encoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        studentRepository.save(userStudent)
        userToken = jwtTokenProvider.createToken("testuser", Role.USER.value)

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
        studentRepository.save(adminStudent)
        adminToken = jwtTokenProvider.createToken("admin", Role.ADMIN.value)
    }

    @AfterAll
    fun tearDownDatabase() {
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
    @DisplayName("Refresh Token으로 인증이 필요한 API에 접근하면 401 Unauthorized가 발생한다")
    fun `Refresh Token으로 보호 API 접근 시 401 Unauthorized`() {
        val refreshToken = jwtTokenProvider.createRefreshToken("testuser")

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
        val refreshToken = refreshTokenService.generateAndSave("testuser")

        try {
            mockMvc.perform(
                post("/api/v1/auth/refresh")
                    .header("Authorization", "Bearer $refreshToken")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.token").isNotEmpty)
                .andExpect(jsonPath("$.refreshToken").isNotEmpty)
        } finally {
            refreshTokenService.revokeAll("testuser")
        }
    }

    @Test
    @DisplayName("다른 사용자의 로그에는 AI 리뷰 피드백을 제출할 수 없다")
    fun `타인 로그 피드백 제출 시 403 Forbidden`() {
        val log = logRepository.save(
            Log(
                title = LogTitle("소유권 검증"),
                content = LogContent("testuser의 로그"),
                code = LogCode("fun main() = Unit"),
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

    companion object {
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
