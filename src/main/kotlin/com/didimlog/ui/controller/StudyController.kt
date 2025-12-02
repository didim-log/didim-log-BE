package com.didimlog.ui.controller

import com.didimlog.application.study.StudyService
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.ui.dto.SolutionSubmitRequest
import com.didimlog.ui.dto.SolutionSubmitResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Study", description = "학습 관련 API")
@RestController
@RequestMapping("/api/v1/study")
class StudyController(
    private val studyService: StudyService,
    private val studentRepository: StudentRepository
) {

    @Operation(
        summary = "문제 풀이 결과 제출",
        description = "학생이 문제를 풀고 결과를 제출합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/submit")
    fun submitSolution(
        authentication: Authentication,
        @Parameter(description = "풀이 결과 정보", required = true)
        @RequestBody
        @Valid
        request: SolutionSubmitRequest
    ): ResponseEntity<SolutionSubmitResponse> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        
        studyService.submitSolution(
            bojId = bojId,
            problemId = request.problemId,
            timeTaken = request.timeTaken,
            isSuccess = request.isSuccess
        )

        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow { IllegalStateException("학생을 찾을 수 없습니다. bojId=$bojId") }

        val response = SolutionSubmitResponse.from(student)
        return ResponseEntity.ok(response)
    }
}


