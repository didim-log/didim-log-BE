package com.didimlog.ui.controller

import com.didimlog.global.system.MaintenanceModeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "System", description = "시스템 제어 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/system")
@PreAuthorize("hasRole('ADMIN')")
class SystemController(
    private val maintenanceModeService: MaintenanceModeService
) {

    @Operation(
        summary = "유지보수 모드 토글",
        description = "서버를 끄지 않고 일반 사용자의 접근만 차단하는 유지보수 모드를 활성화/비활성화합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "토글 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/maintenance")
    fun toggleMaintenanceMode(
        @RequestBody
        @Valid
        request: MaintenanceModeRequest
    ): ResponseEntity<MaintenanceModeResponse> {
        maintenanceModeService.setMaintenanceMode(request.enabled)
        val message = when (request.enabled) {
            true -> "유지보수 모드가 활성화되었습니다."
            false -> "유지보수 모드가 비활성화되었습니다."
        }
        val response = MaintenanceModeResponse(
            enabled = maintenanceModeService.isMaintenanceMode(),
            message = message
        )
        return ResponseEntity.ok(response)
    }
}

/**
 * 유지보수 모드 요청 DTO
 */
data class MaintenanceModeRequest(
    val enabled: Boolean
)

/**
 * 유지보수 모드 응답 DTO
 */
data class MaintenanceModeResponse(
    val enabled: Boolean,
    val message: String
)

