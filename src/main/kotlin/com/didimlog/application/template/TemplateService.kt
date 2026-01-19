package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.global.util.CodeLanguageDetector
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
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
    private val studentRepository: StudentRepository
) {

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
        val template = Template(
            studentId = studentId,
            title = title,
            content = content,
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false, // Deprecated field, kept for DB compatibility
            isDefaultFail = false // Deprecated field, kept for DB compatibility
        )
        return templateRepository.save(template)
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
        val template = getTemplate(templateId)
        if (template.type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 수정할 수 없습니다.")
        }
        template.validateOwner(studentId)
        
        val updatedTemplate = template.update(title, content)
        return templateRepository.save(updatedTemplate)
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
        val template = getTemplate(templateId)
        if (template.type == TemplateOwnershipType.SYSTEM) {
            throw BusinessException(ErrorCode.TEMPLATE_CANNOT_DELETE_SYSTEM, "시스템 템플릿은 삭제할 수 없습니다.")
        }
        template.validateOwner(studentId)
        
        templateRepository.delete(template)
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
        val template = getTemplate(templateId)
        val student = getStudent(studentId)
        
        val updatedStudent = if (category == TemplateCategory.SUCCESS) {
            student.copy(defaultSuccessTemplateId = templateId)
        } else {
            student.copy(defaultFailTemplateId = templateId)
        }
        
        studentRepository.save(updatedStudent)
        return template
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
                return getTemplate(templateId)
            }
            return getSystemDefaultSuccessTemplate()
        }
        
        val templateId = student.defaultFailTemplateId
        if (templateId != null) {
            return getTemplate(templateId)
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
    private fun getSystemDefaultSuccessTemplate(): Template {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        return systemTemplates.firstOrNull { it.title == "Simple(요약)" }
            ?: throw BusinessException(
                ErrorCode.TEMPLATE_NOT_FOUND,
                "시스템 기본 성공 템플릿을 찾을 수 없습니다."
            )
    }

    /**
     * 시스템 기본 실패 템플릿을 조회한다.
     * Detail 템플릿을 기본 실패 템플릿으로 사용한다.
     *
     * @return 시스템 기본 실패 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없는 경우
     */
    private fun getSystemDefaultFailTemplate(): Template {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        return systemTemplates.firstOrNull { it.title == "Detail(상세)" }
            ?: throw BusinessException(
                ErrorCode.TEMPLATE_NOT_FOUND,
                "시스템 기본 실패 템플릿을 찾을 수 없습니다."
            )
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
        code: String? = null
    ): String {
        val template = getTemplate(templateId)
        val problem = getProblem(problemId)
        val timeTaken = getTimeTaken(studentId, problemId)
        val result = getProblemResult(studentId, problemId)
        
        // 프로그래밍 언어가 제공되지 않았고 코드가 있으면 자동 감지
        val detectedLanguage = programmingLanguage ?: detectLanguageFromCode(code)
        
        return renderContent(template.content, problem, timeTaken, result, detectedLanguage)
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
        return problemService.getProblemDetail(problemId)
    }

    /**
     * 학생의 특정 문제 풀이 시간을 조회한다.
     *
     * @param studentId 학생 ID
     * @param problemId 문제 ID
     * @return 포맷팅된 풀이 시간 ("X분 Y초", "X초", 또는 "-")
     */
    private fun getTimeTaken(studentId: String, problemId: Long): String {
        val student = getStudent(studentId)
        val problemIdVo = ProblemId(problemId.toString())
        val solution = student.solutions.findByProblemId(problemIdVo)
        
        if (solution == null) {
            return "-"
        }
        
        return formatTimeTaken(solution.timeTaken.value)
    }

    /**
     * 학생의 특정 문제 풀이 결과를 조회하여 템플릿 매크로용 문자열로 변환한다.
     *
     * @param studentId 학생 ID
     * @param problemId 문제 ID
     * @return 풀이 결과 문자열 ("해결", "미해결", 또는 "해결/미해결")
     */
    private fun getProblemResult(studentId: String, problemId: Long): String {
        val student = getStudent(studentId)
        val problemIdVo = ProblemId(problemId.toString())
        val solution = student.solutions.findByProblemId(problemIdVo)
        
        if (solution == null) {
            return "해결/미해결"
        }
        
        return when (solution.result) {
            ProblemResult.SUCCESS -> "해결"
            ProblemResult.FAIL,
            ProblemResult.TIME_OVER -> "미해결"
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
}
