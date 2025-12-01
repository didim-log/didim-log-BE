package com.didimlog.ui.controller

import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.ui.dto.RetrospectiveRequest
import com.didimlog.ui.dto.RetrospectiveResponse
import com.didimlog.ui.dto.TemplateResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Retrospective", description = "회고 관련 API")
@RestController
@RequestMapping("/api/v1/retrospectives")
class RetrospectiveController(
    private val retrospectiveService: RetrospectiveService
) {

    @Operation(
        summary = "회고 작성",
        description = "학생이 문제 풀이 후 회고를 작성합니다. 이미 작성한 회고가 있으면 수정됩니다."
    )
    @PostMapping
    fun writeRetrospective(
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String,

        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String,

        @Parameter(description = "회고 내용", required = true)
        @RequestBody
        @Valid
        request: RetrospectiveRequest
    ): ResponseEntity<RetrospectiveResponse> {
        val retrospective = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = request.content
        )
        val response = RetrospectiveResponse.from(retrospective)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 조회",
        description = "회고 ID로 회고를 조회합니다."
    )
    @GetMapping("/{retrospectiveId}")
    fun getRetrospective(
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<RetrospectiveResponse> {
        val retrospective = retrospectiveService.getRetrospective(retrospectiveId)
        val response = RetrospectiveResponse.from(retrospective)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 템플릿 생성",
        description = "문제 정보를 바탕으로 회고 작성용 마크다운 템플릿을 생성합니다."
    )
    @GetMapping("/template")
    fun generateTemplate(
        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String
    ): ResponseEntity<TemplateResponse> {
        val template = retrospectiveService.generateTemplate(problemId)
        val response = TemplateResponse(template = template)
        return ResponseEntity.ok(response)
    }
}

