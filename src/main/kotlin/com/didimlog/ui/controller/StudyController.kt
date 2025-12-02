package com.didimlog.ui.controller

import com.didimlog.application.study.StudyService
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.ui.dto.SolutionSubmitRequest
import com.didimlog.ui.dto.SolutionSubmitResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
        description = "학생이 문제를 풀고 결과를 제출합니다. 성공률이 80% 이상이고 최대 티어가 아니면 자동으로 티어가 승급됩니다."
    )
    @PostMapping("/submit")
    fun submitSolution(
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String,

        @Parameter(description = "풀이 결과 정보", required = true)
        @RequestBody
        @Valid
        request: SolutionSubmitRequest
    ): ResponseEntity<SolutionSubmitResponse> {
        studyService.submitSolution(
            studentId = studentId,
            problemId = request.problemId,
            timeTaken = request.timeTaken,
            isSuccess = request.isSuccess
        )

        val student = studentRepository.findById(studentId)
            .orElseThrow { IllegalStateException("학생을 찾을 수 없습니다. id=$studentId") }

        val response = SolutionSubmitResponse.from(student)
        return ResponseEntity.ok(response)
    }
}


