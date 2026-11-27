package com.didimlog.global.interceptor

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.system.MaintenanceModeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

@DisplayName("MaintenanceModeInterceptor 테스트")
class MaintenanceModeInterceptorTest {

    private val maintenanceModeService: MaintenanceModeService = mockk()
    private val provider: ObjectProvider<MaintenanceModeService> = mockk()
    private val interceptor = MaintenanceModeInterceptor(provider)
    private val response = MockHttpServletResponse()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun maintenance(enabled: Boolean) {
        every { provider.getIfAvailable() } returns maintenanceModeService
        every { maintenanceModeService.isMaintenanceMode() } returns enabled
    }

    private fun authenticate(vararg roles: String, authenticated: Boolean = true) {
        val authorities = roles.map(::SimpleGrantedAuthority)
        val authentication = if (authenticated) {
            UsernamePasswordAuthenticationToken("user", null, authorities)
        } else {
            UsernamePasswordAuthenticationToken("user", null)
        }
        SecurityContextHolder.getContext().authentication = authentication
    }

    @Test
    @DisplayName("유지보수 서비스 빈이 없거나 유지보수 모드가 꺼져 있으면 모든 요청을 통과시킨다")
    fun `유지보수 모드 아님`() {
        every { provider.getIfAvailable() } returns null
        assertThat(interceptor.preHandle(MockHttpServletRequest("DELETE", "/api/v1/logs/1"), response, Any())).isTrue()

        maintenance(enabled = false)
        assertThat(interceptor.preHandle(MockHttpServletRequest("DELETE", "/api/v1/logs/1"), response, Any())).isTrue()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET, /api/v1/notices",
        "GET, /api/v1/notices/3",
        "GET, /api/v1/system/status",
        "POST, /api/v1/auth/login",
        "POST, /api/v1/auth/super-admin"
    )
    @DisplayName("유지보수 중에도 공개 API와 로그인 API는 허용한다")
    fun `유지보수 중 공개 API 허용`(method: String, path: String) {
        maintenance(enabled = true)

        assertThat(interceptor.preHandle(MockHttpServletRequest(method, path), response, Any())).isTrue()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "POST, /api/v1/notices",
        "GET, /api/v1/auth/login",
        "POST, /api/v1/auth/login/extra",
        "GET, /api/v1/logs"
    )
    @DisplayName("유지보수 중 비공개 요청은 비로그인 사용자를 차단한다")
    fun `유지보수 중 비공개 요청 차단`(method: String, path: String) {
        maintenance(enabled = true)

        val exception = assertThrows<BusinessException> {
            interceptor.preHandle(MockHttpServletRequest(method, path), response, Any())
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.MAINTENANCE_MODE)
    }

    @Test
    @DisplayName("유지보수 중에는 ADMIN 권한 사용자만 비공개 요청을 사용할 수 있다")
    fun `유지보수 중 관리자 허용`() {
        maintenance(enabled = true)
        val request = MockHttpServletRequest("DELETE", "/api/v1/logs/1")

        authenticate("ROLE_ADMIN")
        assertThat(interceptor.preHandle(request, response, Any())).isTrue()

        authenticate("ROLE_USER")
        assertThrows<BusinessException> { interceptor.preHandle(request, response, Any()) }

        authenticate("ROLE_ADMIN", authenticated = false)
        assertThrows<BusinessException> { interceptor.preHandle(request, response, Any()) }
        verify(atLeast = 3) { maintenanceModeService.isMaintenanceMode() }
    }
}
