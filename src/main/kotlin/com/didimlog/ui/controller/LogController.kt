package com.didimlog.ui.controller

import com.didimlog.application.log.AiReviewService
import com.didimlog.ui.dto.AiReviewResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Log", description = "코딩 로그 관련 API")
@RestController
@RequestMapping("/api/v1/logs")
class LogController(
    private val aiReviewService: AiReviewService
) {

    @Operation(
        summary = "AI 한 줄 리뷰 생성/조회",
        description = "로그 엔티티에서 코드와 언어를 자동으로 추출하여 AI 한 줄 리뷰를 생성하거나 조회합니다. " +
                "캐시 우선으로 동작하며, 코드가 2000자를 초과하면 자동으로 잘라서 분석합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회/생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/{logId}/ai-review")
    fun requestAiReview(
        @PathVariable logId: String
    ): ResponseEntity<AiReviewResponse> {
        val result = aiReviewService.requestOneLineReview(logId)
        return ResponseEntity.ok(AiReviewResponse(review = result.review, cached = result.cached))
    }
}


