package com.didimlog.ui.controller

import com.didimlog.application.template.TemplateService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.template.SectionPreset
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.TemplatePreviewRequest
import com.didimlog.ui.dto.TemplatePresetResponse
import com.didimlog.ui.dto.TemplateRequest
import com.didimlog.ui.dto.TemplateRenderResponse
import com.didimlog.ui.dto.TemplateResponse
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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Template", description = "회고 템플릿 관리 API - 사용자 커스텀 템플릿 및 시스템 기본 템플릿 관리")
@RestController
@RequestMapping("/api/v1/templates")
@org.springframework.validation.annotation.Validated
class TemplateController(
    private val templateService: TemplateService,
    private val studentRepository: StudentRepository
) {

    @Operation(
        summary = "템플릿 목록 조회",
        description = "내 템플릿과 시스템 템플릿 목록을 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping
    fun getTemplates(authentication: Authentication): ResponseEntity<List<TemplateResponse>> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val templates = templateService.getTemplates(studentId)
        val response = templates.map { TemplateResponse.from(it) }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "섹션 프리셋 목록 조회",
        description = "커스텀 템플릿 작성 시 활용할 수 있는 추천 섹션 목록을 조회합니다. 성공(SUCCESS), 실패(FAIL), 공통(COMMON) 카테고리별로 분류되어 제공됩니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/presets")
    fun getSectionPresets(): ResponseEntity<List<TemplatePresetResponse>> {
        val presets = SectionPreset.values().map { TemplatePresetResponse.from(it) }
        return ResponseEntity.ok(presets)
    }

    @Operation(
        summary = "템플릿 미리보기",
        description = "템플릿을 저장하지 않고 미리보기로 렌더링합니다. 매크로 변수를 실제 문제 데이터로 치환하여 결과를 반환합니다. 템플릿 작성 중 매크로가 올바르게 변환되는지 확인할 수 있습니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "미리보기 성공"),
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
                description = "문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/preview")
    fun previewTemplate(
        @Valid @RequestBody request: TemplatePreviewRequest
    ): ResponseEntity<TemplateRenderResponse> {
        val renderedContent = templateService.previewTemplate(
            request.templateContent,
            request.problemId,
            request.programmingLanguage
        )
        val response = TemplateRenderResponse(renderedContent = renderedContent)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "템플릿 렌더링",
        description = "저장된 템플릿을 문제 데이터와 결합하여 렌더링된 템플릿을 반환합니다. 매크로 변수를 실제 문제 데이터로 치환합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "렌더링 성공"),
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
                description = "템플릿 또는 문제를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/{id}/render")
    fun renderTemplate(
        authentication: Authentication,
        @Parameter(description = "템플릿 ID")
        @PathVariable id: String,
        @Parameter(description = "문제 ID", required = true)
        @RequestParam
        @Min(value = 1, message = "문제 ID는 1 이상이어야 합니다.")
        problemId: Long,
        @Parameter(description = "프로그래밍 언어 코드 (선택사항, 예: JAVA, KOTLIN, PYTHON)")
        @RequestParam(required = false)
        programmingLanguage: String?
    ): ResponseEntity<TemplateRenderResponse> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val renderedContent = templateService.renderTemplate(id, problemId, studentId, programmingLanguage)
        val response = TemplateRenderResponse(renderedContent = renderedContent)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "커스텀 템플릿 생성",
        description = "새로운 커스텀 템플릿을 생성합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
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
    fun createTemplate(
        authentication: Authentication,
        @Valid @RequestBody request: TemplateRequest
    ): ResponseEntity<TemplateResponse> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val template = templateService.createTemplate(studentId, request.title, request.content)
        val response = TemplateResponse.from(template)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @Operation(
        summary = "템플릿 수정",
        description = "커스텀 템플릿을 수정합니다. 시스템 템플릿은 수정할 수 없습니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패 또는 시스템 템플릿 수정 시도",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "템플릿 소유자가 아님",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "템플릿을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PutMapping("/{id}")
    fun updateTemplate(
        authentication: Authentication,
        @Parameter(description = "템플릿 ID")
        @PathVariable id: String,
        @Valid @RequestBody request: TemplateRequest
    ): ResponseEntity<TemplateResponse> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val template = templateService.updateTemplate(id, studentId, request.title, request.content)
        val response = TemplateResponse.from(template)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "템플릿 기본값 설정",
        description = "특정 템플릿을 성공 또는 실패용 기본값으로 설정합니다. 기존 기본 템플릿은 자동으로 해제됩니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "설정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 카테고리 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "템플릿 소유자가 아님",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "템플릿을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PutMapping("/{id}/default")
    fun setDefaultTemplate(
        authentication: Authentication,
        @Parameter(description = "템플릿 ID")
        @PathVariable id: String,
        @Parameter(description = "템플릿 카테고리 (SUCCESS 또는 FAIL)", required = true)
        @RequestParam category: String
    ): ResponseEntity<TemplateResponse> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val templateCategory = validateCategory(category)
        val template = templateService.setDefaultTemplate(id, templateCategory, studentId)
        val response = TemplateResponse.from(template)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "기본 템플릿 조회",
        description = "성공 또는 실패용 기본 템플릿을 조회합니다. 사용자가 설정한 기본 템플릿이 없으면 시스템 기본 템플릿을 반환합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 카테고리 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "기본 템플릿을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/default")
    fun getDefaultTemplate(
        authentication: Authentication,
        @Parameter(description = "템플릿 카테고리 (SUCCESS 또는 FAIL)", required = true)
        @RequestParam category: String
    ): ResponseEntity<TemplateResponse> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        val templateCategory = validateCategory(category)
        val template = templateService.getDefaultTemplate(templateCategory, studentId)
        val response = TemplateResponse.from(template)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "템플릿 삭제",
        description = "커스텀 템플릿을 삭제합니다. 시스템 템플릿은 삭제할 수 없습니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "템플릿 소유자가 아님 또는 시스템 템플릿 삭제 시도",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "템플릿을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @DeleteMapping("/{id}")
    fun deleteTemplate(
        authentication: Authentication,
        @Parameter(description = "템플릿 ID")
        @PathVariable id: String
    ): ResponseEntity<Void> {
        val student = getStudentFromAuthentication(authentication)
        val studentId = getStudentId(student)
        templateService.deleteTemplate(id, studentId)
        return ResponseEntity.noContent().build()
    }

    private fun getStudentFromAuthentication(authentication: Authentication): Student {
        val bojId = authentication.name
        return getStudentByBojId(bojId)
    }

    private fun getStudentByBojId(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow { BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId") }
    }

    private fun getStudentId(student: Student): String {
        return student.id
            ?: throw BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생 ID를 찾을 수 없습니다. bojId=${student.bojId?.value}")
    }

    /**
     * 카테고리 문자열을 검증하고 TemplateCategory Enum으로 변환한다.
     *
     * @param category 카테고리 문자열
     * @return TemplateCategory Enum
     * @throws BusinessException 유효하지 않은 카테고리인 경우
     */
    private fun validateCategory(category: String): TemplateCategory {
        return try {
            TemplateCategory.valueOf(category.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "유효하지 않은 카테고리입니다. category=$category (SUCCESS 또는 FAIL을 사용하세요)"
            )
        }
    }
}
