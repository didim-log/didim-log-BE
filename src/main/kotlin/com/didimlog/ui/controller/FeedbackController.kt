package com.didimlog.ui.controller

import com.didimlog.application.feedback.FeedbackService
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.FeedbackResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Feedback", description = "고객의 소리 관련 API")
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
    private val studentRepository: StudentRepository
) {

    @Operation(
        summary = "피드백 등록",
        description = "버그 리포트 또는 건의사항을 등록합니다. JWT 토큰에서 사용자 ID를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping
    fun createFeedback(
        @Valid
        @RequestBody
        request: FeedbackCreateRequest,
        authentication: Authentication
    ): ResponseEntity<FeedbackResponse> {
        val bojId = authentication.name // JWT 토큰의 subject (BOJ ID)
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
        
        val writerId = student.id
            ?: throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "학생 ID를 찾을 수 없습니다.")
        
        val feedback = feedbackService.createFeedback(writerId, request.content, request.type)
        return ResponseEntity.status(HttpStatus.CREATED).body(FeedbackResponse.from(feedback, student))
    }
}

/**
 * 피드백 생성 요청 DTO
 */
data class FeedbackCreateRequest(
    @field:jakarta.validation.constraints.Size(min = 10, message = "피드백 내용은 10자 이상이어야 합니다.")
    val content: String,
    val type: FeedbackType
)
