package com.didimlog.ui.controller

import com.didimlog.application.ai.AiUsageService
import com.didimlog.application.log.AiReviewService
import com.didimlog.application.log.LogService
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AiReviewResponse
import com.didimlog.ui.dto.LogCreateRequest
import com.didimlog.ui.dto.LogFeedbackRequest
import com.didimlog.ui.dto.LogResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Log", description = "코딩 로그 관련 API")
@RestController
@RequestMapping("/api/v1/logs")
@Validated
class LogController(
    private val logService: LogService,
    private val aiReviewService: AiReviewService,
    private val aiUsageService: AiUsageService,
    private val studentRepository: StudentRepository
) {

    @Operation(
        summary = "로그 생성",
        description = "새로운 코딩 로그를 생성합니다. 생성된 로그 ID를 반환하며, 이후 AI 리뷰 생성에 사용할 수 있습니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping
    fun createLog(
        @Parameter(hidden = true)
        authentication: Authentication?,
        @Parameter(description = "로그 생성 정보", required = true)
        @RequestBody
        @Valid
        request: LogCreateRequest
    ): ResponseEntity<LogResponse> {
        val bojId = authentication?.name // JWT 토큰의 subject(bojId), null일 수 있음
        val log = logService.createLog(
            title = request.title,
            content = request.content,
            code = request.code,
            bojId = bojId?.takeIf { it.isNotBlank() },
            isSuccess = request.isSuccess
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(LogResponse.from(log))
    }

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
            ),
            ApiResponse(
                responseCode = "503",
                description = "AI 생성 실패 또는 타임아웃",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/{logId}/ai-review")
    fun requestAiReview(
        @PathVariable
        @NotBlank(message = "로그 ID는 필수입니다.")
        logId: String
    ): ResponseEntity<AiReviewResponse> {
        val result = aiReviewService.requestOneLineReview(logId)
        return ResponseEntity.ok(AiReviewResponse(review = result.review, cached = result.cached))
    }

    @Operation(
        summary = "AI 리뷰 피드백 제출",
        description = "AI 리뷰에 대한 사용자 피드백을 제출합니다. LIKE 또는 DISLIKE를 선택할 수 있으며, DISLIKE의 경우 이유를 함께 제출할 수 있습니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "피드백 제출 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "로그를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/{logId}/feedback")
    fun submitFeedback(
        @Parameter(description = "로그 ID", required = true)
        @PathVariable
        @NotBlank(message = "로그 ID는 필수입니다.")
        logId: String,
        @Parameter(description = "피드백 정보", required = true)
        @RequestBody
        @Valid
        request: com.didimlog.ui.dto.LogFeedbackRequest
    ): ResponseEntity<Map<String, String>> {
        logService.updateFeedback(logId, request.status, request.reason)
        return ResponseEntity.ok(mapOf("message" to "피드백이 제출되었습니다."))
    }

    @Operation(
        summary = "AI 사용량 조회",
        description = "현재 사용자의 AI 사용량 정보를 조회합니다. 일일 제한, 현재 사용량, 남은 사용량, 서비스 활성화 여부를 반환합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "BOJ 인증이 완료되지 않은 사용자",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/ai-usage/me")
    fun getMyAiUsage(
        @Parameter(hidden = true)
        authentication: Authentication?
    ): ResponseEntity<com.didimlog.ui.dto.AiUsageResponse> {
        val bojId = authentication?.name
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED, "인증이 필요합니다.")
        
        val student = studentRepository.findByBojId(BojId(bojId))
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
        
        val usageInfo = aiUsageService.getUserUsage(bojId)
        
        return ResponseEntity.ok(
            com.didimlog.ui.dto.AiUsageResponse(
                limit = usageInfo.limit,
                usage = usageInfo.usage,
                remaining = usageInfo.remaining,
                isServiceEnabled = usageInfo.isServiceEnabled
            )
        )
    }
}


