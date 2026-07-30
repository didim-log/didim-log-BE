package com.didimlog.application.template

import com.didimlog.application.problem.ProblemService
import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.global.util.CodeLanguageDetector
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.repository.TemplateSummaryView
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 템플릿 관리 서비스
 * 템플릿의 CRUD 및 렌더링 로직을 담당한다.
 */
@Service
class TemplateService(
    private val templateRepository: TemplateRepository,
    private val problemService: ProblemService,
    private val studentRepository: StudentRepository,
    private val studentLifecycleCoordinator: StudentLifecycleCoordinator
) {
    private val log = LoggerFactory.getLogger(TemplateService::class.java)

    data class TemplateSummaryProjection(
        val id: String,
        val studentId: String?,
        val title: String,
        val type: String,
        val createdAt: java.time.LocalDateTime,
        val updatedAt: java.time.LocalDateTime
    )


    /**
     * 특정 학생의 템플릿과 시스템 템플릿을 모두 조회한다.
     *
     * @param studentId 학생 ID
     * @return 템플릿 목록
     */
    @Transactional(readOnly = true)
    fun getTemplates(studentId: String): List<Template> {
        return templateRepository.findByStudentIdOrType(studentId, TemplateOwnershipType.SYSTEM)
    }

    /**
     * 특정 학생의 템플릿 요약 정보(본문 제외)를 조회한다.
     */
    @Transactional(readOnly = true)
    fun getTemplateSummaries(studentId: String): List<TemplateSummaryProjection> {
        return templateRepository.findSummaryByStudentIdOrType(studentId, TemplateOwnershipType.SYSTEM)
            .map { it.toProjection() }
    }

    /**
     * 목록에 포함된 템플릿을 기준으로 실제 기본 템플릿 ID를 결정한다.
     * 삭제되었거나 다른 사용자가 소유한 ID는 시스템 기본값으로 대체한다.
     */
    @Transactional(readOnly = true)
    fun resolveDefaultTemplateIds(
        student: Student,
        availableTemplateIds: Set<String>
    ): Pair<String?, String?> {
        val studentId = student.id
            ?: throw BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생 ID를 찾을 수 없습니다.")
        val successTemplateId = student.defaultSuccessTemplateId
            ?.takeIf(availableTemplateIds::contains)
            ?: getDefaultTemplate(TemplateCategory.SUCCESS, studentId).id
        val failTemplateId = student.defaultFailTemplateId
            ?.takeIf(availableTemplateIds::contains)
            ?: getDefaultTemplate(TemplateCategory.FAIL, studentId).id
        return successTemplateId to failTemplateId
    }

    /**
     * 특정 템플릿을 조회한다.
     *
     * @param templateId 템플릿 ID
     * @return 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getTemplate(templateId: String): Template {
        return templateRepository.findById(templateId)
            .orElseThrow { BusinessException(ErrorCode.TEMPLATE_NOT_FOUND, "템플릿을 찾을 수 없습니다. id=$templateId") }
    }

    /**
     * 커스텀 템플릿을 생성한다.
     * 
     * Note: isDefaultSuccess와 isDefaultFail은 DB 호환성을 위해 false로 설정되지만,
     * 비즈니스 로직에서는 사용되지 않습니다. 기본 템플릿은 Student 엔티티가 관리합니다.
     *
     * @param studentId 학생 ID
     * @param title 템플릿 제목
     * @param content 템플릿 내용
     * @return 생성된 템플릿
     */
    @Transactional
    fun createTemplate(studentId: String, title: String, content: String): Template {
        return studentLifecycleCoordinator.execute(studentId) {
            getStudent(studentId)
            val template = Template(
                studentId = studentId,
                title = title,
                content = content,
                type = TemplateOwnershipType.CUSTOM,
                isDefaultSuccess = false, // Deprecated field, kept for DB compatibility
                isDefaultFail = false // Deprecated field, kept for DB compatibility
            )
            templateRepository.save(template)
        }
    }

    /**
     * 템플릿을 수정한다.
     * 시스템 템플릿은 수정할 수 없다.
     *
     * @param templateId 템플릿 ID
     * @param studentId 학생 ID (소유권 검증용)
     * @param title 새로운 제목
     * @param content 새로운 내용
     * @return 수정된 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun updateTemplate(templateId: String, studentId: String, title: String, content: String): Template {
        return studentLifecycleCoordinator.execute(studentId) {
            getStudent(studentId)
            val template = getTemplate(templateId)
            if (template.type == TemplateOwnershipType.SYSTEM) {
                throw BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "시스템 템플릿은 수정할 수 없습니다."
                )
            }
            validateOwner(template, studentId)

            templateRepository.save(template.update(title, content))
        }
    }

    /**
     * 템플릿을 삭제한다.
     * 시스템 템플릿은 삭제할 수 없다.
     *
     * @param templateId 템플릿 ID
     * @param studentId 학생 ID (소유권 검증용)
     * @throws BusinessException 템플릿을 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun deleteTemplate(templateId: String, studentId: String) {
        studentLifecycleCoordinator.execute(studentId) {
            val student = getStudent(studentId)
            val template = getTemplate(templateId)
            if (template.type == TemplateOwnershipType.SYSTEM) {
                throw BusinessException(
                    ErrorCode.TEMPLATE_CANNOT_DELETE_SYSTEM,
                    "시스템 템플릿은 삭제할 수 없습니다."
                )
            }
            validateOwner(template, studentId)

            val defaultCategories = student.defaultTemplateCategories(templateId)
            if (defaultCategories.isNotEmpty()) {
                studentRepository.clearDefaultTemplateReferences(
                    studentId = studentId,
                    expectedTemplateId = templateId,
                    categories = defaultCategories
                ) ?: throw BusinessException(
                    ErrorCode.SESSION_STATE_CONFLICT,
                    "기본 템플릿 참조가 변경되어 삭제를 완료하지 못했습니다."
                )
            }
            templateRepository.delete(template)
        }
    }

    /**
     * 특정 템플릿을 기본값으로 설정한다.
     * Student 엔티티의 필드를 업데이트하여 기본 템플릿을 설정한다.
     * 시스템 템플릿도 기본값으로 설정할 수 있다.
     *
     * @param templateId 템플릿 ID
     * @param category 템플릿 카테고리 (SUCCESS 또는 FAIL)
     * @param studentId 학생 ID
     * @return 기본값으로 설정된 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없는 경우
     */
    @Transactional
    fun setDefaultTemplate(templateId: String, category: TemplateCategory, studentId: String): Template {
        return studentLifecycleCoordinator.execute(studentId) {
            getStudent(studentId)
            val template = getTemplate(templateId)
            if (template.type == TemplateOwnershipType.CUSTOM) {
                validateOwner(template, studentId)
            }
            studentRepository.updateDefaultTemplateById(studentId, category, templateId)
                ?: throw BusinessException(
                    ErrorCode.STUDENT_NOT_FOUND,
                    "학생을 찾을 수 없습니다. id=$studentId"
                )
            template
        }
    }

    /**
     * 카테고리별 기본 템플릿을 조회한다.
     * Student 엔티티의 필드를 먼저 확인하고, 값이 있으면 해당 템플릿을 반환한다.
     * 값이 없으면 시스템 기본 템플릿을 반환한다.
     *
     * @param category 템플릿 카테고리 (SUCCESS 또는 FAIL)
     * @param studentId 학생 ID
     * @return 기본 템플릿
     * @throws BusinessException 기본 템플릿을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getDefaultTemplate(category: TemplateCategory, studentId: String): Template {
        val student = getStudent(studentId)
        
        if (category == TemplateCategory.SUCCESS) {
            val templateId = student.defaultSuccessTemplateId
            if (templateId != null) {
                val template = resolveStudentDefaultTemplate(templateId, studentId)
                if (template != null) {
                    return template
                }
                log.warn("유효하지 않은 성공 기본 템플릿 ID를 감지하여 시스템 기본값으로 fallback 합니다. studentId={}, templateId={}", studentId, templateId)
            }
            return getSystemDefaultSuccessTemplate()
        }
        
        val templateId = student.defaultFailTemplateId
        if (templateId != null) {
            val template = resolveStudentDefaultTemplate(templateId, studentId)
            if (template != null) {
                return template
            }
            log.warn("유효하지 않은 실패 기본 템플릿 ID를 감지하여 시스템 기본값으로 fallback 합니다. studentId={}, templateId={}", studentId, templateId)
        }
        return getSystemDefaultFailTemplate()
    }

    /**
     * 시스템 기본 성공 템플릿을 조회한다.
     * Simple 템플릿을 기본 성공 템플릿으로 사용한다.
     *
     * @return 시스템 기본 성공 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없는 경우
     */
    @Suppress("DEPRECATION")
    private fun getSystemDefaultSuccessTemplate(): Template {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        return systemTemplates.firstOrNull { it.isDefaultSuccess }
            ?: systemTemplates.firstOrNull { !it.isDefaultFail }
            ?: throw BusinessException(
                ErrorCode.TEMPLATE_NOT_FOUND,
                "시스템 기본 성공 템플릿을 찾을 수 없습니다. defaultSuccess 플래그를 확인해주세요."
            )
    }

    /**
     * 시스템 기본 실패 템플릿을 조회한다.
     * Detail 템플릿을 기본 실패 템플릿으로 사용한다.
     *
     * @return 시스템 기본 실패 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없는 경우
     */
    @Suppress("DEPRECATION")
    private fun getSystemDefaultFailTemplate(): Template {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        return systemTemplates.firstOrNull { it.isDefaultFail }
            ?: systemTemplates.firstOrNull { !it.isDefaultSuccess }
            ?: throw BusinessException(
                ErrorCode.TEMPLATE_NOT_FOUND,
                "시스템 기본 실패 템플릿을 찾을 수 없습니다. defaultFail 플래그를 확인해주세요."
            )
    }

    private fun resolveStudentDefaultTemplate(templateId: String, studentId: String): Template? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        if (template.type == TemplateOwnershipType.CUSTOM && template.studentId != studentId) {
            return null
        }
        return template
    }

    private fun validateOwner(template: Template, studentId: String) {
        if (!template.isOwner(studentId)) {
            throw BusinessException(
                ErrorCode.ACCESS_DENIED,
                "본인이 소유한 템플릿만 변경할 수 있습니다."
            )
        }
    }

    /**
     * 템플릿을 렌더링한다.
     * 템플릿 내의 매크로 변수를 실제 문제 데이터로 치환한다.
     *
     * 지원 매크로:
     * - {{problemId}}: 문제 ID
     * - {{problemTitle}}: 문제 제목
     * - {{tier}}: 티어 (예: GOLD_3)
     * - {{language}}: 문제 설명 언어를 대문자로 변환 (예: "ko" -> "KO", "en" -> "EN")
     * - {{link}}: 문제 링크
     * - {{timeTaken}}: 풀이 소요 시간 (예: "3분 14초", "30초", 기록 없으면 "-")
     * - {{result}}: 풀이 결과 (예: "해결", "미해결", 기록이 없으면 "해결/미해결")
     * - {{site}}: 문제 출처 사이트 이름 (예: "백준/BOJ")
     *
     * @param templateId 템플릿 ID
     * @param problemId 문제 ID
     * @param studentId 학생 ID (timeTaken 조회용)
     * @param programmingLanguage 프로그래밍 언어 코드 (선택사항, 제공되지 않으면 코드에서 자동 감지)
     * @param code 제출한 코드 (선택사항, programmingLanguage가 없을 때 언어 감지에 사용)
     * @return 렌더링된 템플릿 내용
     * @throws BusinessException 템플릿 또는 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun renderTemplate(
        templateId: String,
        problemId: Long,
        studentId: String,
        programmingLanguage: String? = null,
        code: String? = null,
        resultTypeHint: ProblemResult? = null
    ): String {
        val problem = getProblem(problemId)
        val studentProblemInfo = getStudentProblemInfo(studentId, problemId)
        val fallbackCategory = resolveFallbackTemplateCategory(
            resultTypeHint = resultTypeHint,
            studentProblemResult = studentProblemInfo.problemResultType
        )
        val template = resolveTemplateForRender(templateId, studentId, fallbackCategory)
        
        // 프로그래밍 언어가 제공되지 않았고 코드가 있으면 자동 감지
        val detectedLanguage = programmingLanguage ?: detectLanguageFromCode(code)
        
        return renderContent(
            template.content,
            problem,
            studentProblemInfo.timeTaken,
            studentProblemInfo.result,
            detectedLanguage
        )
    }

    /**
     * 템플릿 렌더 타임아웃 등으로 정상 렌더링이 불가능한 경우 사용할 fallback 렌더링.
     * 요청 템플릿 ID와 무관하게 사용자의 기본 템플릿(성공/실패 기준)을 사용한다.
     */
    @Transactional(readOnly = true)
    fun renderFallbackTemplate(
        problemId: Long,
        studentId: String,
        programmingLanguage: String? = null,
        code: String? = null,
        resultTypeHint: ProblemResult? = null
    ): String {
        val problem = getProblem(problemId)
        val studentProblemInfo = getStudentProblemInfo(studentId, problemId)
        val fallbackCategory = resolveFallbackTemplateCategory(
            resultTypeHint = resultTypeHint,
            studentProblemResult = studentProblemInfo.problemResultType
        )
        val fallbackTemplate = getDefaultTemplate(fallbackCategory, studentId)
        val detectedLanguage = programmingLanguage ?: detectLanguageFromCode(code)
        return renderContent(
            fallbackTemplate.content,
            problem,
            studentProblemInfo.timeTaken,
            studentProblemInfo.result,
            detectedLanguage
        )
    }

    /**
     * 템플릿 내용을 렌더링한다.
     * 코드 블록 내의 {{language}}는 프로그래밍 언어로 치환하고,
     * 일반 텍스트의 {{language}}는 문제 설명 언어로 치환한다.
     *
     * @param content 템플릿 내용
     * @param problem 문제 정보
     * @param timeTaken 풀이 소요 시간 (기록 없으면 "-")
     * @param result 풀이 결과 ("해결", "미해결", 또는 "해결/미해결")
     * @param programmingLanguage 프로그래밍 언어 코드 (선택사항, 기본값: "TEXT")
     * @return 렌더링된 내용
     */
    private fun renderContent(
        content: String,
        problem: Problem,
        timeTaken: String = "-",
        result: String = "해결/미해결",
        programmingLanguage: String? = null
    ): String {
        var rendered = content
        
        // 코드 블록 내의 {{language}}를 프로그래밍 언어로 먼저 치환
        val codeBlockPattern = Regex("```\\{\\{language\\}\\}(\\n|$)")
        val programmingLangTag = convertToMarkdownLanguageTag(programmingLanguage)
        rendered = codeBlockPattern.replace(rendered) { matchResult ->
            "```$programmingLangTag${matchResult.groupValues[1]}"
        }
        
        // 일반 텍스트의 {{language}}는 문제 설명 언어로 치환
        rendered = rendered.replace("{{problemId}}", problem.id.value)
        rendered = rendered.replace("{{problemTitle}}", problem.title)
        rendered = rendered.replace("{{tier}}", problem.difficulty.name)
        rendered = rendered.replace("{{language}}", problem.language.uppercase())
        rendered = rendered.replace("{{link}}", problem.url)
        rendered = rendered.replace("{{timeTaken}}", timeTaken)
        rendered = rendered.replace("{{result}}", result)
        rendered = rendered.replace("{{site}}", "백준/BOJ")
        
        return rendered
    }

    /**
     * 프로그래밍 언어 코드를 마크다운 코드 블록 태그로 변환한다.
     * 대문자 Enum 값(예: "JAVA", "KOTLIN")을 소문자 마크다운 태그(예: "java", "kotlin")로 변환한다.
     *
     * @param programmingLanguage 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON")
     * @return 마크다운 코드 블록 태그 (예: "java", "kotlin", "python"), 기본값: "text"
     */
    private fun convertToMarkdownLanguageTag(programmingLanguage: String?): String {
        if (programmingLanguage == null) {
            return "text"
        }
        
        return try {
            val language = PrimaryLanguage.valueOf(programmingLanguage.uppercase())
            language.value
        } catch (e: IllegalArgumentException) {
            // 유효하지 않은 언어 코드인 경우 기본값 반환
            "text"
        }
    }

    /**
     * 템플릿 내용을 미리보기로 렌더링한다.
     * DB에 저장하지 않고, 메모리 상에서만 매크로 치환을 수행한다.
     *
     * @param templateContent 템플릿 내용 (매크로 포함)
     * @param problemId 문제 ID
     * @param programmingLanguage 프로그래밍 언어 코드 (선택사항, 제공되지 않으면 코드에서 자동 감지)
     * @param code 제출한 코드 (선택사항, programmingLanguage가 없을 때 언어 감지에 사용)
     * @return 렌더링된 템플릿 내용
     * @throws BusinessException 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun previewTemplate(
        templateContent: String,
        problemId: Long,
        programmingLanguage: String? = null,
        code: String? = null
    ): String {
        val problem = getProblem(problemId)
        
        // 프로그래밍 언어가 제공되지 않았고 코드가 있으면 자동 감지
        val detectedLanguage = programmingLanguage ?: detectLanguageFromCode(code)
        
        return renderContent(templateContent, problem, programmingLanguage = detectedLanguage)
    }

    /**
     * 코드에서 프로그래밍 언어를 자동 감지한다.
     * CodeLanguageDetector를 사용하여 가중치 기반 언어 감지를 수행한다.
     *
     * @param code 제출한 코드
     * @return 감지된 프로그래밍 언어 코드 (예: "JAVA", "KOTLIN", "PYTHON"), 코드가 없으면 null
     */
    private fun detectLanguageFromCode(code: String?): String? {
        if (code == null || code.isBlank()) {
            return null
        }
        return CodeLanguageDetector.detect(code)
    }

    /**
     * 문제를 조회한다.
     *
     * @param problemId 문제 ID
     * @return 문제
     * @throws BusinessException 문제를 찾을 수 없는 경우
     */
    private fun getProblem(problemId: Long): Problem {
        // 템플릿 렌더링 경로는 외부 의존(solved.ac)을 타지 않고 DB 우선으로 조회한다.
        // DB 미존재/조회 실패 시에도 템플릿 작성 UX를 깨지 않기 위해 최소 문제 정보로 fallback 한다.
        return runCatching { problemService.getProblemMetaIfExists(problemId) }
            .getOrNull()
            ?: createFallbackProblem(problemId)
    }

    private fun createFallbackProblem(problemId: Long): Problem {
        return Problem(
            id = ProblemId(problemId.toString()),
            title = "문제 $problemId",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 1,
            url = "https://www.acmicpc.net/problem/$problemId",
            language = "ko"
        )
    }

    /**
     * 학생의 특정 문제 풀이 시간을 조회한다.
     *
     * @param studentId 학생 ID
     * @param problemId 문제 ID
     * @return 포맷팅된 풀이 시간 ("X분 Y초", "X초", 또는 "-")
     */
    private data class StudentProblemInfo(
        val timeTaken: String,
        val result: String,
        val problemResultType: ProblemResult?
    )

    /**
     * 학생 + 문제 기준으로 템플릿 렌더링에 필요한 정보(풀이시간/결과)를 한 번에 계산한다.
     */
    private fun getStudentProblemInfo(studentId: String, problemId: Long): StudentProblemInfo {
        val student = getStudent(studentId)
        val problemIdVo = ProblemId(problemId.toString())
        val solution = student.solutions.findByProblemId(problemIdVo)

        if (solution == null) {
            return StudentProblemInfo(
                timeTaken = "-",
                result = "해결/미해결",
                problemResultType = null
            )
        }

        val resultText = when (solution.result) {
            ProblemResult.SUCCESS -> "해결"
            ProblemResult.FAIL,
            ProblemResult.TIME_OVER -> "미해결"
        }
        return StudentProblemInfo(
            timeTaken = formatTimeTaken(solution.timeTaken.value),
            result = resultText,
            problemResultType = solution.result
        )
    }

    private fun resolveTemplateForRender(
        templateId: String,
        studentId: String,
        fallbackCategory: TemplateCategory
    ): Template {
        val template = templateRepository.findById(templateId).orElse(null)
        if (template == null) {
            log.warn(
                "렌더링 대상 템플릿이 없어 기본 템플릿으로 fallback 합니다. templateId={}, studentId={}, fallbackCategory={}",
                templateId,
                studentId,
                fallbackCategory
            )
            return getDefaultTemplate(fallbackCategory, studentId)
        }

        if (template.type == TemplateOwnershipType.CUSTOM && template.studentId != studentId) {
            throw BusinessException(
                ErrorCode.ACCESS_DENIED,
                "접근 권한이 없는 템플릿입니다. templateId=$templateId"
            )
        }

        return template
    }

    private fun resolveFallbackTemplateCategory(
        resultTypeHint: ProblemResult?,
        studentProblemResult: ProblemResult?
    ): TemplateCategory {
        val effectiveResult = resultTypeHint ?: studentProblemResult
        return if (effectiveResult == ProblemResult.SUCCESS) {
            TemplateCategory.SUCCESS
        } else if (effectiveResult == null) {
            TemplateCategory.SUCCESS
        } else {
            TemplateCategory.FAIL
        }
    }

    /**
     * 학생을 조회한다.
     *
     * @param studentId 학생 ID
     * @return 학생
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    private fun getStudent(studentId: String): Student {
        return studentRepository.findById(studentId)
            .orElseThrow { BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. id=$studentId") }
    }

    /**
     * 풀이 시간(초)을 "X분 Y초" 또는 "X초" 형식으로 포맷팅한다.
     *
     * @param seconds 풀이 시간(초)
     * @return 포맷팅된 시간 문자열 (예: "3분 14초", "30초")
     */
    private fun formatTimeTaken(seconds: Long): String {
        if (seconds < 60) {
            return "${seconds}초"
        }
        
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        
        if (remainingSeconds == 0L) {
            return "${minutes}분"
        }
        
        return "${minutes}분 ${remainingSeconds}초"
    }

    private fun TemplateSummaryView.toProjection(): TemplateSummaryProjection {
        return TemplateSummaryProjection(
            id = this.id ?: "",
            studentId = this.studentId,
            title = this.title,
            type = this.type.name,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    }
}
