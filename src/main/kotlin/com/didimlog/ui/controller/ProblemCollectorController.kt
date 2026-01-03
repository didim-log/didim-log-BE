package com.didimlog.ui.controller

import com.didimlog.application.ProblemCollectorService
import com.didimlog.ui.dto.ProblemStatsResponse
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "문제 데이터 수집 관련 API (관리자용)")
@RestController
@RequestMapping("/api/v1/admin/problems")
@Validated
class ProblemCollectorController(
    private val problemCollectorService: ProblemCollectorService
) {

    @Operation(
        summary = "문제 메타데이터 수집",
        description = "Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 수집하여 DB에 저장합니다. (Upsert 방식)",
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
        problemCollectorService.collectMetadata(start, end)
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 메타데이터 수집이 완료되었습니다.",
                "range" to "$start-$end"
            )
        )
    }

    @Operation(
        summary = "문제 상세 정보 크롤링",
        description = "DB에서 description이 null인 문제들의 상세 정보를 BOJ 사이트에서 크롤링하여 업데이트합니다. Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둡니다.",
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
        problemCollectorService.collectDetailsBatch()
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 상세 정보 크롤링이 완료되었습니다."
            )
        )
    }

    @Operation(
        summary = "문제 통계 조회",
        description = "DB에 저장된 문제의 총 개수, 최소 문제 ID, 최대 문제 ID를 조회합니다. 관리자가 다음 크롤링 범위를 결정하는 데 사용합니다.",
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
    @GetMapping("/stats")
    fun getProblemStats(
        authentication: Authentication
    ): ResponseEntity<ProblemStatsResponse> {
        val stats = problemCollectorService.getProblemStats()
        return ResponseEntity.ok(stats)
    }
}

