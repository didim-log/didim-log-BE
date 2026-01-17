package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
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
    private val problemService: ProblemService
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
     * 기존 기본 템플릿은 자동으로 해제된다.
     *
     * @param templateId 템플릿 ID
     * @param category 템플릿 카테고리 (SUCCESS 또는 FAIL)
     * @return 기본값으로 설정된 템플릿
     * @throws BusinessException 템플릿을 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun setDefaultTemplate(templateId: String, category: TemplateCategory): Template {
        val template = getTemplate(templateId)
        validateTemplateOwnership(template)
        
        val studentId = getStudentIdFromTemplate(template)
        
        if (category == TemplateCategory.SUCCESS) {
            unsetExistingDefaultSuccess(studentId, templateId)
            val updatedTemplate = template.setAsDefaultSuccess()
            return templateRepository.save(updatedTemplate)
        }
        
        unsetExistingDefaultFail(studentId, templateId)
        val updatedTemplate = template.setAsDefaultFail()
        return templateRepository.save(updatedTemplate)
    }

    /**
     * 카테고리별 기본 템플릿을 조회한다.
     * 사용자가 설정한 기본 템플릿이 없으면 시스템 템플릿을 반환한다.
     *
     * @param category 템플릿 카테고리 (SUCCESS 또는 FAIL)
     * @param studentId 학생 ID
     * @return 기본 템플릿
     * @throws BusinessException 기본 템플릿을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getDefaultTemplate(category: TemplateCategory, studentId: String): Template {
        if (category == TemplateCategory.SUCCESS) {
            val userDefault = templateRepository.findByStudentIdAndIsDefaultSuccessTrue(studentId)
            if (userDefault != null) {
                return userDefault
            }
            return getSystemDefaultSuccessTemplate()
        }
        
        val userDefault = templateRepository.findByStudentIdAndIsDefaultFailTrue(studentId)
        if (userDefault != null) {
            return userDefault
        }
        return getSystemDefaultFailTemplate()
    }

    /**
     * 템플릿 소유권을 검증한다.
     * SYSTEM 템플릿은 기본값으로 설정할 수 없다.
     *
     * @param template 템플릿
     * @throws IllegalArgumentException 시스템 템플릿인 경우
     */
    private fun validateTemplateOwnership(template: Template) {
        if (template.type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 기본 템플릿으로 설정할 수 없습니다.")
        }
    }

    /**
     * 템플릿에서 학생 ID를 추출한다.
     *
     * @param template 템플릿
     * @return 학생 ID
     * @throws IllegalArgumentException 템플릿에 학생 ID가 없는 경우
     */
    private fun getStudentIdFromTemplate(template: Template): String {
        return template.studentId
            ?: throw IllegalArgumentException("커스텀 템플릿은 소유자가 필요합니다.")
    }

    /**
     * 기존 성공용 기본 템플릿을 해제한다.
     *
     * @param studentId 학생 ID
     * @param excludeTemplateId 제외할 템플릿 ID
     */
    private fun unsetExistingDefaultSuccess(studentId: String, excludeTemplateId: String) {
        val existingDefault = templateRepository.findByStudentIdAndIsDefaultSuccessTrue(studentId)
        if (existingDefault != null && existingDefault.id != excludeTemplateId) {
            val unsetTemplate = existingDefault.unsetDefaultSuccess()
            templateRepository.save(unsetTemplate)
        }
    }

    /**
     * 기존 실패용 기본 템플릿을 해제한다.
     *
     * @param studentId 학생 ID
     * @param excludeTemplateId 제외할 템플릿 ID
     */
    private fun unsetExistingDefaultFail(studentId: String, excludeTemplateId: String) {
        val existingDefault = templateRepository.findByStudentIdAndIsDefaultFailTrue(studentId)
        if (existingDefault != null && existingDefault.id != excludeTemplateId) {
            val unsetTemplate = existingDefault.unsetDefaultFail()
            templateRepository.save(unsetTemplate)
        }
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
     * - {{language}}: 문제 설명 언어 (예: ko, en)
     * - {{link}}: 문제 링크
     *
     * @param templateId 템플릿 ID
     * @param problemId 문제 ID
     * @return 렌더링된 템플릿 내용
     * @throws BusinessException 템플릿 또는 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun renderTemplate(templateId: String, problemId: Long): String {
        val template = getTemplate(templateId)
        val problem = getProblem(problemId)
        
        return renderContent(template.content, problem)
    }

    /**
     * 템플릿 내용을 렌더링한다.
     *
     * @param content 템플릿 내용
     * @param problem 문제 정보
     * @return 렌더링된 내용
     */
    private fun renderContent(content: String, problem: Problem): String {
        var rendered = content
        
        rendered = rendered.replace("{{problemId}}", problem.id.value)
        rendered = rendered.replace("{{problemTitle}}", problem.title)
        rendered = rendered.replace("{{tier}}", problem.difficulty.name)
        rendered = rendered.replace("{{language}}", problem.language)
        rendered = rendered.replace("{{link}}", problem.url)
        
        return rendered
    }

    /**
     * 템플릿 내용을 미리보기로 렌더링한다.
     * DB에 저장하지 않고, 메모리 상에서만 매크로 치환을 수행한다.
     *
     * @param templateContent 템플릿 내용 (매크로 포함)
     * @param problemId 문제 ID
     * @return 렌더링된 템플릿 내용
     * @throws BusinessException 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun previewTemplate(templateContent: String, problemId: Long): String {
        val problem = getProblem(problemId)
        return renderContent(templateContent, problem)
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
}
