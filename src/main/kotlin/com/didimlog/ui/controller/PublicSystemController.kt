package com.didimlog.ui.controller

import com.didimlog.global.system.MaintenanceModeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Public System", description = "공개 시스템 상태 API (인증 불필요)")
@RestController
@RequestMapping("/api/v1/system")
class PublicSystemController(
    private val maintenanceModeService: MaintenanceModeService
) {

    @Operation(
        summary = "시스템 상태 조회",
        description = "서버의 유지보수 모드 상태를 조회합니다. 인증 없이 접근 가능합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공")
        ]
    )
    @GetMapping("/status")
    fun getSystemStatus(): ResponseEntity<SystemStatusResponse> {
        val isMaintenance = maintenanceModeService.isMaintenanceMode()
        val response = SystemStatusResponse(
            underMaintenance = isMaintenance,
            maintenanceMessage = if (isMaintenance) {
                "서버 점검 중입니다. 잠시 후 다시 시도해주세요."
            } else {
                null
            }
        )
        return ResponseEntity.ok(response)
    }
}

/**
 * 시스템 상태 응답 DTO
 */
data class SystemStatusResponse(
    val underMaintenance: Boolean,
    val maintenanceMessage: String? = null
)

