package com.didimlog.ui.controller

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.BookmarkToggleResponse
import com.didimlog.ui.dto.RetrospectivePageResponse
import com.didimlog.ui.dto.RetrospectiveRequest
import com.didimlog.ui.dto.RetrospectiveResponse
import com.didimlog.ui.dto.TemplateResponse
import com.didimlog.application.template.StaticTemplateService
import com.didimlog.ui.dto.StaticTemplateRequest
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Retrospective", description = "회고 관련 API")
@RestController
@RequestMapping("/api/v1/retrospectives")
@org.springframework.validation.annotation.Validated
class RetrospectiveController(
    private val retrospectiveService: RetrospectiveService,
    private val staticTemplateService: StaticTemplateService,
    private val studentRepository: StudentRepository
) {

    @Operation(
        summary = "회고 작성",
        description = "학생이 문제 풀이 후 회고를 작성합니다. 이미 작성한 회고가 있으면 수정됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다. " +
                "요청 본문에 content(필수, 10자 이상), summary(선택, 200자 이하), " +
                "resultType(선택, SUCCESS/FAIL), solvedCategory(선택, 50자 이하)를 포함할 수 있습니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작성/수정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패 또는 잘못된 입력",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "학생 또는 문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping
    fun writeRetrospective(
        authentication: Authentication,
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String,
        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String,

        @Parameter(description = "회고 내용", required = true)
        @RequestBody
        @Valid
        request: RetrospectiveRequest
    ): ResponseEntity<RetrospectiveResponse> {
        // 1. JWT 토큰에서 현재 사용자 정보 추출
        val bojId = authentication.name
        val currentStudent = getStudentByBojId(bojId)

        // 2. 쿼리 파라미터의 studentId와 JWT 토큰의 사용자 일치 여부 검증
        validateStudentIdMatch(currentStudent.id, studentId)

        // 3. 회고 작성 (RetrospectiveService에서 추가 소유권 검증 수행)
        val retrospective = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = request.content,
            summary = request.summary,
            solutionResult = request.resultType,
            solvedCategory = request.solvedCategory
        )
        val response = RetrospectiveResponse.from(retrospective)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 목록 조회",
        description = "검색 조건에 따라 회고 목록을 조회합니다. 키워드, 카테고리, 북마크 여부로 필터링할 수 있으며, 페이징을 지원합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 요청 파라미터(페이지/정렬/카테고리 등)",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping
    fun getRetrospectives(
        @Parameter(description = "검색 키워드 (제목 또는 내용)", required = false)
        @RequestParam(required = false)
        keyword: String?,

        @Parameter(description = "카테고리 필터", required = false)
        @RequestParam(required = false)
        category: String?,

        @Parameter(description = "북마크 여부 (true인 경우만 필터링)", required = false)
        @RequestParam(required = false)
        isBookmarked: Boolean?,

        @Parameter(description = "학생 ID", required = false)
        @RequestParam(required = false)
        studentId: String?,

        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,

        @Parameter(description = "페이지 크기", required = false)
        @RequestParam(defaultValue = "10")
        size: Int,

        @Parameter(description = "정렬 기준 (예: createdAt,desc 또는 createdAt,asc)", required = false)
        @RequestParam(required = false)
        sort: String?
    ): ResponseEntity<RetrospectivePageResponse> {
        val pageable = createPageable(page, size, sort)
        val condition = RetrospectiveSearchCondition(
            keyword = keyword,
            category = category?.let { parseProblemCategory(it) },
            isBookmarked = isBookmarked,
            studentId = studentId
        )

        val pageResult = retrospectiveService.searchRetrospectives(condition, pageable)
        val response = RetrospectivePageResponse.from(pageResult)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 조회",
        description = "회고 ID로 회고를 조회합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "404",
                description = "회고를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
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
        summary = "북마크 토글",
        description = "회고의 북마크 상태를 토글합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "토글 성공"),
            ApiResponse(
                responseCode = "404",
                description = "회고를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/{retrospectiveId}/bookmark")
    fun toggleBookmark(
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<BookmarkToggleResponse> {
        val isBookmarked = retrospectiveService.toggleBookmark(retrospectiveId)
        val response = BookmarkToggleResponse(isBookmarked = isBookmarked)
        return ResponseEntity.ok(response)
    }

    private fun createPageable(page: Int, size: Int, sort: String?): Pageable {
        if (sort == null) {
            return PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        }

        val parts = sort.split(",")
        if (parts.size != 2) {
            return PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        }

        val direction = if (parts[1].lowercase() == "asc") Sort.Direction.ASC else Sort.Direction.DESC
        val sortObj = Sort.by(direction, parts[0])
        return PageRequest.of(page - 1, size, sortObj)
    }

    private fun parseProblemCategory(raw: String): ProblemCategory {
        val normalized = raw.trim()
        if (normalized.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "category는 공백일 수 없습니다.")
        }
        return try {
            ProblemCategory.valueOf(normalized.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 category 입니다. category=$raw")
        }
    }

    /**
     * BOJ ID로 Student를 조회한다.
     *
     * @param bojId BOJ ID
     * @return Student
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    private fun getStudentByBojId(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
    }

    /**
     * 쿼리 파라미터의 studentId와 JWT 토큰의 사용자 ID가 일치하는지 검증한다.
     *
     * @param currentStudentId 현재 로그인한 사용자의 Student ID
     * @param requestedStudentId 쿼리 파라미터로 전달된 Student ID
     * @throws BusinessException 일치하지 않는 경우 (403 Forbidden)
     */
    private fun validateStudentIdMatch(currentStudentId: String?, requestedStudentId: String) {
        if (currentStudentId == null) {
            throw BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생 ID를 찾을 수 없습니다.")
        }
        if (currentStudentId != requestedStudentId) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, "회고를 작성할 권한이 없습니다. studentId=$requestedStudentId")
        }
    }

    @Operation(
        summary = "회고 삭제",
        description = "회고 ID로 회고를 삭제합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공 (응답 본문 없음)"),
            ApiResponse(
                responseCode = "404",
                description = "회고를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @DeleteMapping("/{retrospectiveId}")
    fun deleteRetrospective(
        authentication: Authentication,
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<Void> {
        // JWT 토큰에서 현재 사용자 정보 추출
        val bojId = authentication.name
        val currentStudent = getStudentByBojId(bojId)
        val studentId = currentStudent.id
            ?: throw BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생 ID를 찾을 수 없습니다. bojId=$bojId")

        // 소유권 검증 포함된 삭제
        retrospectiveService.deleteRetrospective(retrospectiveId, studentId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @Operation(
        summary = "회고 템플릿 생성",
        description = "문제 정보를 바탕으로 회고 작성용 마크다운 템플릿을 생성합니다. " +
                "resultType(SUCCESS/FAIL)에 따라 다른 템플릿이 생성됩니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 요청 파라미터(resultType 등)",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/template")
    fun generateTemplate(
        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String,
        
        @Parameter(description = "풀이 결과 타입 (SUCCESS/FAIL)", required = true)
        @RequestParam resultType: com.didimlog.domain.enums.ProblemResult
    ): ResponseEntity<TemplateResponse> {
        val template = retrospectiveService.generateTemplate(problemId, resultType)
        val response = TemplateResponse(template = template)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "정적 회고 템플릿 생성",
        description = "AI 서비스 없이 정적 템플릿을 생성하여 반환합니다. 문제 카테고리, 사용자 코드, 에러 메시지(실패 시)를 포함한 기본 템플릿을 제공합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "템플릿 생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/template/static")
    fun generateStaticTemplate(
        @RequestBody
        @Valid
        request: StaticTemplateRequest
    ): ResponseEntity<TemplateResponse> {
        val template = staticTemplateService.generateRetrospectiveTemplate(
            problemId = request.problemId,
            code = request.code,
            isSuccess = request.isSuccess,
            errorMessage = request.errorMessage
        )
        val response = TemplateResponse(template = template)
        return ResponseEntity.ok(response)
    }
}
