package com.didimlog.ui.controller

import com.didimlog.application.ProblemService
import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.ui.dto.ProblemDetailResponse
import com.didimlog.ui.dto.ProblemResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Problem", description = "문제 관련 API")
@RestController
@RequestMapping("/api/v1/problems")
@Validated
class ProblemController(
    private val recommendationService: RecommendationService,
    private val problemService: ProblemService
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
        @Parameter(description = "추천할 문제 개수 (기본값: 10, 최소: 10, 최대: 50)", required = false)
        @RequestParam(defaultValue = "10")
        @Min(value = 10, message = "추천 개수는 최소 10개 이상이어야 합니다.")
        @Max(value = 50, message = "추천 개수는 최대 50개 이하여야 합니다.")
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

    @Operation(
        summary = "문제 상세 조회",
        description = "문제 ID로 문제 상세 정보를 조회합니다. DB에 상세 정보가 없으면 백준 웹사이트에서 실시간으로 크롤링하여 가져옵니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 problemId 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/{problemId}")
    fun getProblemDetail(
        @Parameter(description = "문제 ID", required = true)
        @PathVariable
        @Positive(message = "문제 ID는 1 이상이어야 합니다.")
        problemId: Long
    ): ResponseEntity<ProblemDetailResponse> {
        val problem = problemService.getProblemDetail(problemId)
        val response = ProblemDetailResponse.from(problem)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 검색",
        description = "문제 번호로 문제를 검색합니다. DB에 문제가 없으면 Solved.ac API로 메타데이터를 조회하고 크롤링하여 저장한 후 반환합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 problemId 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/search")
    fun searchProblem(
        @Parameter(description = "문제 번호", required = true)
        @RequestParam
        @Positive(message = "문제 번호는 1 이상이어야 합니다.")
        q: Long
    ): ResponseEntity<ProblemDetailResponse> {
        val problem = problemService.getProblemDetail(q)
        val response = ProblemDetailResponse.from(problem)
        return ResponseEntity.ok(response)
    }
}
