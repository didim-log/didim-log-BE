package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminService
import com.didimlog.application.feedback.FeedbackService
import com.didimlog.domain.Quote
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.ui.dto.AdminUserResponse
import com.didimlog.ui.dto.AdminUserUpdateDto
import com.didimlog.ui.dto.FeedbackResponse
import com.didimlog.ui.dto.QuoteResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "관리자 관련 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin")
@Validated
class AdminController(
    private val adminService: AdminService,
    private val feedbackService: FeedbackService,
    private val studentRepository: com.didimlog.domain.repository.StudentRepository
) {

    @Operation(
        summary = "전체 회원 목록 조회",
        description = "페이징을 적용하여 전체 회원 목록을 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 page/size 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/users")
    fun getAllUsers(
        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 20)", required = false)
        @RequestParam(defaultValue = "20")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int,
        @Parameter(description = "검색어 (닉네임, BOJ ID, 이메일)", required = false)
        @RequestParam(required = false)
        search: String?,
        @Parameter(description = "가입 시작일 (ISO 8601 형식, 예: 2024-01-01)", required = false)
        @RequestParam(required = false)
        startDate: String?,
        @Parameter(description = "가입 종료일 (ISO 8601 형식, 예: 2024-12-31)", required = false)
        @RequestParam(required = false)
        endDate: String?
    ): ResponseEntity<Page<AdminUserResponse>> {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "rating"))
        val users = adminService.getAllUsers(pageable, search, startDate, endDate)
        return ResponseEntity.ok(users)
    }

    @Operation(
        summary = "회원 강제 탈퇴",
        description = "특정 회원을 강제로 탈퇴시킵니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "탈퇴 처리 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "학생을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @DeleteMapping("/users/{studentId}")
    fun deleteUser(
        @Parameter(description = "학생 ID")
        @PathVariable
        studentId: String
    ): ResponseEntity<Map<String, String>> {
        adminService.deleteUser(studentId)
        return ResponseEntity.ok(mapOf("message" to "회원이 성공적으로 탈퇴되었습니다."))
    }

    @Operation(
        summary = "사용자 정보 강제 수정",
        description = "특정 사용자의 권한(Role), 닉네임, BOJ ID를 선택적으로 수정합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정 성공 (응답 본문 없음)"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 입력 또는 닉네임/BOJ ID 중복",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "학생을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "409",
                description = "BOJ ID 중복",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/users/{studentId}")
    fun updateUser(
        @Parameter(description = "학생 ID")
        @PathVariable
        studentId: String,
        @Valid
        @RequestBody
        request: AdminUserUpdateDto
    ): ResponseEntity<Void> {
        adminService.updateUser(studentId, request)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @Operation(
        summary = "명언 목록 조회",
        description = "페이징을 적용하여 명언 목록을 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 page/size 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/quotes")
    fun getAllQuotes(
        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 20)", required = false)
        @RequestParam(defaultValue = "20")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int
    ): ResponseEntity<Page<QuoteResponse>> {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"))
        val quotes = adminService.getAllQuotes(pageable)
        val response = quotes.map { QuoteResponse.from(it) }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "명언 추가",
        description = "새로운 명언을 추가합니다. ADMIN 권한이 필요합니다.",
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
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/quotes")
    fun createQuote(
        @Valid
        @RequestBody
        request: QuoteCreateRequest
    ): ResponseEntity<QuoteResponse> {
        val quote = adminService.createQuote(request.content, request.author)
        return ResponseEntity.status(HttpStatus.CREATED).body(QuoteResponse.from(quote))
    }

    @Operation(
        summary = "명언 삭제",
        description = "특정 명언을 삭제합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "명언을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @DeleteMapping("/quotes/{quoteId}")
    fun deleteQuote(
        @Parameter(description = "명언 ID")
        @PathVariable
        quoteId: String
    ): ResponseEntity<Map<String, String>> {
        adminService.deleteQuote(quoteId)
        return ResponseEntity.ok(mapOf("message" to "명언이 성공적으로 삭제되었습니다."))
    }

    @Operation(
        summary = "피드백 목록 조회",
        description = "페이징을 적용하여 피드백 목록을 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 page/size 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/feedbacks")
    fun getAllFeedbacks(
        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 20)", required = false)
        @RequestParam(defaultValue = "20")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int
    ): ResponseEntity<Page<FeedbackResponse>> {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val feedbacks = feedbackService.getAllFeedbacks(pageable)
        val response = feedbacks.map { feedback ->
            val student = studentRepository.findById(feedback.writerId).orElse(null)
            if (student != null) {
                FeedbackResponse.from(feedback, student)
            } else {
                FeedbackResponse.from(feedback)
            }
        }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "피드백 상태 변경",
        description = "피드백의 처리 상태를 변경합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "변경 성공"),
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
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "피드백을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PatchMapping("/feedbacks/{feedbackId}/status")
    fun updateFeedbackStatus(
        @Parameter(description = "피드백 ID")
        @PathVariable
        feedbackId: String,
        @Valid
        @RequestBody
        request: FeedbackStatusUpdateRequest
    ): ResponseEntity<FeedbackResponse> {
        val feedback = feedbackService.updateFeedbackStatus(feedbackId, request.status)
        val student = studentRepository.findById(feedback.writerId).orElse(null)
        val response = if (student != null) {
            FeedbackResponse.from(feedback, student)
        } else {
            FeedbackResponse.from(feedback)
        }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "피드백 삭제",
        description = "완료된 피드백을 삭제합니다. 완료되지 않은 피드백은 삭제할 수 없습니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "400",
                description = "완료되지 않은 피드백은 삭제할 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "피드백을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @DeleteMapping("/feedbacks/{feedbackId}")
    fun deleteFeedback(
        @Parameter(description = "피드백 ID")
        @PathVariable
        feedbackId: String
    ): ResponseEntity<Unit> {
        feedbackService.deleteFeedback(feedbackId)
        return ResponseEntity.noContent().build()
    }
}

/**
 * 피드백 상태 변경 요청 DTO
 */
data class FeedbackStatusUpdateRequest(
    @field:NotNull(message = "상태 값은 필수입니다.")
    val status: FeedbackStatus
)

/**
 * 명언 생성 요청 DTO
 */
data class QuoteCreateRequest(
    @field:NotBlank(message = "명언 내용은 필수입니다.")
    val content: String,
    @field:NotBlank(message = "저자명은 필수입니다.")
    val author: String
)
