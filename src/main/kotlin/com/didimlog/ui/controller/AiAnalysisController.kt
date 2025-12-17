package com.didimlog.ui.controller

import com.didimlog.application.ai.AiAnalysisService
import com.didimlog.ui.dto.AiAnalyzeRequest
import com.didimlog.ui.dto.AiAnalyzeResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "AI", description = "AI 분석 API")
@RestController
@RequestMapping("/api/v1/ai")
class AiAnalysisController(
    private val aiAnalysisService: AiAnalysisService
) {

    @Operation(
        summary = "회고록 AI 생성",
        description = "풀이 성공 여부에 따라 성공 회고 또는 실패 회고를 AI가 생성하여 마크다운으로 반환합니다. 문제 설명 요약, 사용자 코드, 핵심 분석, 개선점이 포함된 완성된 회고록을 생성합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "AI 회고록 생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패 또는 요청 본문 형식 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류 또는 LLM 연동 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/analyze")
    fun analyze(
        @RequestBody
        @Valid
        request: AiAnalyzeRequest
    ): ResponseEntity<AiAnalyzeResponse> {
        val markdown = aiAnalysisService.analyze(
            code = request.code,
            problemId = request.problemId,
            isSuccess = request.isSuccess
        )
        return ResponseEntity.ok(AiAnalyzeResponse(markdown = markdown))
    }
}

