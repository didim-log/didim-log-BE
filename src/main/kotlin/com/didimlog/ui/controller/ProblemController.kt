package com.didimlog.ui.controller

import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.ui.dto.ProblemResponse
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
        description = "학생의 현재 티어보다 한 단계 높은 난이도(UserLevel + 1 ~ +2)의 문제 중, 아직 풀지 않은 문제를 추천합니다. 카테고리를 지정하면 해당 카테고리 문제만 추천합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "추천 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 count 또는 category 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
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
    @GetMapping("/recommend")
    fun recommendProblems(
        authentication: Authentication,
        @Parameter(description = "추천할 문제 개수 (기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Positive(message = "추천 개수는 1 이상이어야 합니다.")
        count: Int,
        @Parameter(description = "문제 카테고리 (선택사항, 예: IMPLEMENTATION, GRAPH, DP 등)", required = false)
        @RequestParam(required = false)
        category: String?
    ): ResponseEntity<List<ProblemResponse>> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        val problems = recommendationService.recommendProblems(bojId, count, category)
        val response = problems.map { ProblemResponse.from(it) }
        return ResponseEntity.ok(response)
    }
}
