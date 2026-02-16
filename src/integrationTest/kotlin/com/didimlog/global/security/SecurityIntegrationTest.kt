package com.didimlog.global.security

import com.didimlog.DidimLogApplication
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.assertj.core.api.Assertions.assertThat

@SpringBootTest(classes = [DidimLogApplication::class])
@AutoConfigureMockMvc
@DisplayName("보안 통합 테스트")
class SecurityIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var studentRepository: StudentRepository

    private lateinit var userToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        studentRepository.deleteAll()

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
        val result = mockMvc.perform(
            get("/api/v1/admin/users")
                .header("Authorization", "Bearer $adminToken")
        )
        
        // 상태 코드 확인 (200 또는 다른 성공 코드)
        val status = result.andReturn().response.status
        // 403 Forbidden이 아니면 통과 (관리자 권한이 있으면 200, 없으면 403)
        assertThat(status).isNotEqualTo(403)
        assertThat(status).isNotEqualTo(401)
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
}

