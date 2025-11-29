package com.didimlog.ui.controller

import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.ui.dto.ProblemResponse
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

@Tag(name = "Problem", description = "문제 관련 API")
@RestController
@RequestMapping("/api/v1/problems")
@Validated
class ProblemController(
    private val recommendationService: RecommendationService
) {

    @Operation(
        summary = "문제 추천",
        description = "학생의 현재 티어보다 한 단계 높은 난이도의 문제 중, 아직 풀지 않은 문제를 추천합니다."
    )
    @GetMapping("/recommend")
    fun recommendProblems(
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String,

        @Parameter(description = "추천할 문제 개수 (기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Positive(message = "추천 개수는 1 이상이어야 합니다.")
        count: Int
    ): ResponseEntity<List<ProblemResponse>> {
        val problems = recommendationService.recommendProblems(studentId, count)
        val response = problems.map { ProblemResponse.from(it) }
        return ResponseEntity.ok(response)
    }
}

