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
        summary = "회고 섹션 AI 분석",
        description = "회고 특정 섹션(리팩토링/원인분석/반례 등)만 AI가 분석하여 마크다운으로 반환합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "AI 분석 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패 또는 sectionType/요청 본문 형식 오류",
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
            sectionType = request.sectionType,
            isSuccess = request.isSuccess
        )
        return ResponseEntity.ok(AiAnalyzeResponse(sectionType = request.sectionType, markdown = markdown))
    }
}

