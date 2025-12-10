package com.didimlog.ui.controller

import com.didimlog.application.ranking.RankingService
import com.didimlog.ui.dto.LeaderboardResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
        description = "Rating(점수) 기준 상위 랭킹을 조회합니다. 동점자 처리: rating이 같으면 solvedCount(푼 문제 수)가 많은 순, 그것도 같으면 가입일 순으로 정렬합니다."
    )
    @GetMapping
    fun getRankings(
        @Parameter(description = "조회할 상위 랭킹 수 (기본값: 100, 최대: 1000)", required = false)
        @RequestParam(defaultValue = "100")
        @Positive(message = "limit은 1 이상이어야 합니다.")
        limit: Int
    ): ResponseEntity<List<LeaderboardResponse>> {
        val validLimit = limit.coerceAtMost(1000) // 최대 1000명으로 제한
        val rankers = rankingService.getTopRankers(validLimit)
        val response = rankers.map { LeaderboardResponse.from(it.student, it.rank) }
        return ResponseEntity.ok(response)
    }
}
