package com.didimlog.ui.controller

import com.didimlog.application.DetailsCollectJobStatus
import com.didimlog.application.LanguageUpdateJobStatus
import com.didimlog.application.MetadataCollectJobStatus
import com.didimlog.application.ProblemCollectorService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin - Problem Collection", description = "문제 데이터 수집 관련 API (관리자 전용)")
@RestController
@RequestMapping("/api/v1/admin/problems")
@Validated
class ProblemCollectorController(
    private val problemCollectorService: ProblemCollectorService
) {

    @Operation(
        summary = "문제 메타데이터 수집 (비동기)",
        description = "Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 비동기로 수집하여 DB에 저장합니다. (Upsert 방식) 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수집 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 start/end 값",
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
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류 또는 외부 API 연동 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/collect-metadata")
    fun collectMetadata(
        authentication: Authentication,
        @Parameter(description = "시작 문제 ID", required = true)
        @RequestParam
        @Positive(message = "시작 문제 ID는 1 이상이어야 합니다.")
        start: Int,
        @Parameter(description = "종료 문제 ID (포함)", required = true)
        @RequestParam
        @Positive(message = "종료 문제 ID는 1 이상이어야 합니다.")
        end: Int
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.collectMetadataAsync(start, end)
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 메타데이터 수집 작업이 시작되었습니다.",
                "jobId" to jobId,
                "range" to "$start-$end"
            )
        )
    }

    @Operation(
        summary = "문제 메타데이터 수집 작업 상태 조회",
        description = "문제 메타데이터 수집 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
            ApiResponse(
                responseCode = "404",
                description = "작업을 찾을 수 없음",
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
    @GetMapping("/collect-metadata/status/{jobId}")
    fun getMetadataCollectJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<MetadataCollectJobStatus> {
        val status = problemCollectorService.getMetadataCollectJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 상세 정보 크롤링 (비동기)",
        description = "DB에서 description이 null인 문제들의 상세 정보를 BOJ 사이트에서 비동기로 크롤링하여 업데이트합니다. Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둡니다. 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수집 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류 또는 크롤링 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/collect-details")
    fun collectDetails(
        authentication: Authentication
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.collectDetailsBatchAsync()
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 상세 정보 크롤링 작업이 시작되었습니다.",
                "jobId" to jobId
            )
        )
    }

    @Operation(
        summary = "문제 상세 정보 수집 작업 상태 조회",
        description = "문제 상세 정보 수집 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
            ApiResponse(
                responseCode = "404",
                description = "작업을 찾을 수 없음",
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
    @GetMapping("/collect-details/status/{jobId}")
    fun getDetailsCollectJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<DetailsCollectJobStatus> {
        val status = problemCollectorService.getDetailsCollectJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 언어 정보 최신화 (비동기)",
        description = "DB에서 언어 정보가 null인 문제들의 언어 정보를 비동기로 업데이트합니다. 작업 ID를 반환하며, 실제 작업은 백그라운드에서 진행됩니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작업 시작 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/update-language")
    fun updateLanguage(
        authentication: Authentication
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.updateLanguageBatchAsync()
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 언어 정보 최신화 작업이 시작되었습니다.",
                "jobId" to jobId
            )
        )
    }

    @Operation(
        summary = "언어 정보 업데이트 작업 상태 조회",
        description = "언어 정보 업데이트 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
            ApiResponse(
                responseCode = "404",
                description = "작업을 찾을 수 없음",
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
    @GetMapping("/update-language/status/{jobId}")
    fun getLanguageUpdateJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<LanguageUpdateJobStatus> {
        val status = problemCollectorService.getLanguageUpdateJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }
}

