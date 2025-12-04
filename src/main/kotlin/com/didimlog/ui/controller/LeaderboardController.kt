package com.didimlog.ui.controller

import com.didimlog.application.leaderboard.LeaderboardService
import com.didimlog.ui.dto.LeaderboardResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Leaderboard", description = "랭킹 관련 API")
@RestController
@RequestMapping("/api/v1/ranks")
class LeaderboardController(
    private val leaderboardService: LeaderboardService
) {

    @Operation(
        summary = "랭킹 조회",
        description = "Rating(점수) 기준 상위 100명의 랭킹을 조회합니다. 동점자 처리: 점수가 같을 경우 먼저 가입한 순서로 정렬합니다."
    )
    @GetMapping
    fun getRankings(): ResponseEntity<List<LeaderboardResponse>> {
        val rankers = leaderboardService.getTopRankers()
        val response = rankers.map { LeaderboardResponse.from(it.student, it.rank) }
        return ResponseEntity.ok(response)
    }
}

