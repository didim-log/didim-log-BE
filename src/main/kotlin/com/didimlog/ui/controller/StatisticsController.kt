package com.didimlog.ui.controller

import com.didimlog.application.statistics.StatisticsService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.HeatmapDataResponse
import com.didimlog.ui.dto.StatisticsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Statistics", description = "통계 관련 API")
@RestController
@RequestMapping("/api/v1/statistics")
@Validated
class StatisticsController(
    private val statisticsService: StatisticsService
) {

    @Operation(
        summary = "통계 조회",
        description = "학생의 월별 잔디(Heatmap), 카테고리별 분포, 누적 풀이 수를 포함한 통계 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
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
                responseCode = "404",
                description = "학생을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping
    fun getStatistics(
        authentication: Authentication
    ): ResponseEntity<StatisticsResponse> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        val statisticsInfo = statisticsService.getStatistics(bojId)
        val response = StatisticsResponse.from(statisticsInfo)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "연도별 히트맵 조회",
        description = "특정 연도의 활동 히트맵 데이터를 조회합니다. 해당 연도의 1월 1일부터 12월 31일까지의 회고 데이터를 집계합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
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
                responseCode = "404",
                description = "학생을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 연도 파라미터",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/heatmap")
    fun getHeatmapByYear(
        authentication: Authentication,
        @RequestParam(required = false, defaultValue = "0")
        @Min(value = 0, message = "연도는 0 이상이어야 합니다. (0은 현재 연도)")
        @Max(value = 2100, message = "연도는 2100년 이하여야 합니다.")
        year: Int
    ): ResponseEntity<List<HeatmapDataResponse>> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        
        // year가 0이면 현재 연도 사용
        if (year == 0) {
            val currentYear = java.time.LocalDate.now().year
            val heatmapData = statisticsService.getHeatmapByYear(bojId, currentYear)
            val response = heatmapData.map { HeatmapDataResponse.from(it) }
            return ResponseEntity.ok(response)
        }
        
        // 연도 유효성 검증 (1900 ~ 2100)
        if (year < 1900) {
            throw BusinessException(
                ErrorCode.COMMON_VALIDATION_FAILED,
                "연도는 1900년 이상이어야 합니다. 입력된 연도: $year"
            )
        }
        
        val heatmapData = statisticsService.getHeatmapByYear(bojId, year)
        val response = heatmapData.map { HeatmapDataResponse.from(it) }
        return ResponseEntity.ok(response)
    }
}

