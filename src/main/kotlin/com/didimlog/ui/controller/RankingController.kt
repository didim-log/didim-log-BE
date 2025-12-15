package com.didimlog.ui.controller

import com.didimlog.application.ranking.RankingService
import com.didimlog.ui.dto.LeaderboardResponse
import com.didimlog.domain.enums.RankingPeriod
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Ranking", description = "랭킹 조회 관련 API")
@RestController
@RequestMapping("/api/v1/ranks")
@Validated
class RankingController(
    private val rankingService: RankingService
) {

    @Operation(
        summary = "랭킹 조회",
        description = "회고(Retrospective) 작성 수 기준 상위 랭킹을 조회합니다. 동점자는 같은 순위로 처리합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 limit 또는 period 값",
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
    fun getRankings(
        @Parameter(description = "조회할 상위 랭킹 수 (기본값: 100, 최대: 1000)", required = false)
        @RequestParam(defaultValue = "100")
        @Positive(message = "limit은 1 이상이어야 합니다.")
        limit: Int,
        @Parameter(description = "집계 기간 (DAILY, WEEKLY, MONTHLY, TOTAL)", required = false)
        @RequestParam(defaultValue = "TOTAL")
        period: RankingPeriod
    ): ResponseEntity<List<LeaderboardResponse>> {
        val validLimit = limit.coerceAtMost(1000) // 최대 1000명으로 제한
        val rankers = rankingService.getTopRankers(validLimit, period)
        val response = rankers.map { LeaderboardResponse.from(it.student, it.rank, it.retrospectiveCount) }
        return ResponseEntity.ok(response)
    }
}
