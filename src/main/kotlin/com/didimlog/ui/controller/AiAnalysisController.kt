package com.didimlog.ui.controller

import com.didimlog.application.ai.AiAnalysisService
import com.didimlog.ui.dto.AiAnalyzeRequest
import com.didimlog.ui.dto.AiAnalyzeResponse
import io.swagger.v3.oas.annotations.Operation
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
    @PostMapping("/analyze")
    fun analyze(
        @RequestBody
        @Valid
        request: AiAnalyzeRequest
    ): ResponseEntity<AiAnalyzeResponse> {
        val markdown = aiAnalysisService.analyze(
            code = request.code,
            problemId = request.problemId,
            sectionType = request.sectionType
        )
        return ResponseEntity.ok(AiAnalyzeResponse(sectionType = request.sectionType, markdown = markdown))
    }
}

