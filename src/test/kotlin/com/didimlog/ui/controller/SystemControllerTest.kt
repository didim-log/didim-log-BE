package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.application.ai.AiUsageService
import com.didimlog.application.storage.StorageManagementService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.global.system.MaintenanceModeService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AdminSystemController Maintenance 테스트")
@WebMvcTest(
    controllers = [AdminSystemController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, SystemControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var maintenanceModeService: MaintenanceModeService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun maintenanceModeService(): MaintenanceModeService = mockk(relaxed = true)

        @Bean
        fun aiUsageService(): AiUsageService = mockk(relaxed = true)

        @Bean
        fun storageManagementService(): StorageManagementService = mockk(relaxed = true)

        @Bean
        fun adminAuditService(): AdminAuditService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("유지보수 모드 활성화 시 200 OK 및 enabled: true 반환")
    fun `유지보수 모드 활성화 성공`() {
        // given
        val request = mapOf("enabled" to true)
        clearMocks(maintenanceModeService)
        every { maintenanceModeService.setMaintenanceMode(true) } returns Unit
        every { maintenanceModeService.isMaintenanceMode() } returns true

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/system/maintenance")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.message").value("유지보수 모드가 활성화되었습니다."))

        verify(exactly = 1) { maintenanceModeService.setMaintenanceMode(true) }
        verify(exactly = 1) { maintenanceModeService.isMaintenanceMode() }
    }

    @Test
    @DisplayName("유지보수 모드 비활성화 시 200 OK 및 enabled: false 반환")
    fun `유지보수 모드 비활성화 성공`() {
        // given
        val request = mapOf("enabled" to false)
        clearMocks(maintenanceModeService)
        every { maintenanceModeService.setMaintenanceMode(false) } returns Unit
        every { maintenanceModeService.isMaintenanceMode() } returns false

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/system/maintenance")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.message").value("유지보수 모드가 비활성화되었습니다."))

        verify(exactly = 1) { maintenanceModeService.setMaintenanceMode(false) }
        verify(exactly = 1) { maintenanceModeService.isMaintenanceMode() }
    }

    @Test
    @DisplayName("유지보수 모드 설정 시 예상치 못한 예외가 발생하면 500 Internal Server Error 반환")
    fun `유지보수 모드 설정 - 500`() {
        // given
        val request = mapOf("enabled" to true)
        clearMocks(maintenanceModeService)
        every { maintenanceModeService.setMaintenanceMode(true) } throws RuntimeException("unexpected")

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/system/maintenance")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
    }
}

