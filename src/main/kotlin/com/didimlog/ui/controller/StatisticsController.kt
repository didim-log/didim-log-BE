package com.didimlog.ui.controller

import com.didimlog.application.statistics.StatisticsService
import com.didimlog.ui.dto.StatisticsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Statistics", description = "통계 관련 API")
@RestController
@RequestMapping("/api/v1/statistics")
class StatisticsController(
    private val statisticsService: StatisticsService
) {

    @Operation(
        summary = "통계 조회",
        description = "학생의 월별 잔디(Heatmap), 카테고리별 분포, 누적 풀이 수를 포함한 통계 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
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
}

