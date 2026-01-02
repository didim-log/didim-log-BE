package com.didimlog.ui.controller

import com.didimlog.application.ai.AiUsageService
import com.didimlog.application.storage.StorageManagementService
import com.didimlog.ui.dto.AiStatusResponse
import com.didimlog.ui.dto.AiStatusUpdateRequest
import com.didimlog.ui.dto.AiLimitsUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin System", description = "관리자 시스템 제어 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/system")
@Validated
class AdminSystemController(
    private val aiUsageService: AiUsageService,
    private val storageManagementService: StorageManagementService
) {

    @Operation(
        summary = "AI 서비스 상태 조회",
        description = "AI 서비스의 현재 상태(활성화 여부, 사용량, 제한값)를 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
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
    @GetMapping("/ai-status")
    fun getAiStatus(): ResponseEntity<AiStatusResponse> {
        val status = aiUsageService.getStatus()
        val response = AiStatusResponse(
            isEnabled = status.isEnabled,
            todayGlobalUsage = status.todayGlobalUsage,
            globalLimit = status.globalLimit,
            userLimit = status.userLimit
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "AI 서비스 활성화/비활성화",
        description = "AI 서비스를 수동으로 활성화 또는 비활성화합니다. 긴급 상황에서 서비스를 중지할 수 있습니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "상태 변경 성공"),
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
    @PostMapping("/ai-status")
    fun updateAiStatus(
        @Valid @RequestBody request: AiStatusUpdateRequest
    ): ResponseEntity<AiStatusResponse> {
        aiUsageService.setServiceEnabled(request.enabled)
        val status = aiUsageService.getStatus()
        val response = AiStatusResponse(
            isEnabled = status.isEnabled,
            todayGlobalUsage = status.todayGlobalUsage,
            globalLimit = status.globalLimit,
            userLimit = status.userLimit
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "AI 사용량 제한 업데이트",
        description = "AI 서비스의 전역 일일 제한 및 사용자 일일 제한을 동적으로 업데이트합니다. 서버 재시작 없이 즉시 적용됩니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "제한 업데이트 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
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
    @PostMapping("/ai-limits")
    fun updateAiLimits(
        @Valid @RequestBody request: AiLimitsUpdateRequest
    ): ResponseEntity<AiStatusResponse> {
        aiUsageService.updateLimits(request.globalLimit, request.userLimit)
        val status = aiUsageService.getStatus()
        val response = AiStatusResponse(
            isEnabled = status.isEnabled,
            todayGlobalUsage = status.todayGlobalUsage,
            globalLimit = status.globalLimit,
            userLimit = status.userLimit
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "저장 공간 통계 조회",
        description = "회고 데이터의 저장 공간 사용량 통계를 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
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
    @GetMapping("/storage")
    fun getStorageStats(): ResponseEntity<StorageStatsResponse> {
        val stats = storageManagementService.getStats()
        val response = StorageStatsResponse(
            totalCount = stats.totalCount,
            estimatedSizeKb = stats.estimatedSizeKb,
            oldestRecordDate = stats.oldestRecordDate.toString()
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "오래된 회고 데이터 정리",
        description = "지정된 일수보다 오래된 회고 데이터를 삭제합니다. 최소 30일 이상의 데이터만 삭제할 수 있습니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "정리 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패 (최소 30일 이상 필요)",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
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
    @DeleteMapping("/storage/cleanup")
    fun cleanupStorage(
        @Parameter(description = "기준일 (이보다 오래된 데이터 삭제, 최소 30일)", required = true)
        @RequestParam
        @Min(value = 30, message = "최소 30일 이상의 데이터만 삭제할 수 있습니다.")
        olderThanDays: Int
    ): ResponseEntity<StorageCleanupResponse> {
        val deletedCount = storageManagementService.deleteOldRetrospectives(olderThanDays)
        val response = StorageCleanupResponse(
            message = "${deletedCount}개의 회고가 삭제되었습니다.",
            deletedCount = deletedCount
        )
        return ResponseEntity.ok(response)
    }
}

/**
 * 저장 공간 통계 응답 DTO
 */
data class StorageStatsResponse(
    val totalCount: Long,
    val estimatedSizeKb: Long,
    val oldestRecordDate: String // ISO 8601 형식 (YYYY-MM-DD)
)

/**
 * 저장 공간 정리 응답 DTO
 */
data class StorageCleanupResponse(
    val message: String,
    val deletedCount: Long
)

