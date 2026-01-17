package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("TemplateService 테스트")
class TemplateServiceTest {

    private val templateRepository: TemplateRepository = mockk()
    private val problemService: ProblemService = mockk()
    private val service = TemplateService(templateRepository, problemService)

    private val studentId = "student1"
    private val templateId = "template1"

    @Test
    @DisplayName("템플릿 목록을 조회한다")
    fun `템플릿 목록 조회`() {
        // given
        val systemTemplate = Template(
            id = "system1",
            studentId = null,
            title = "Simple(요약)",
            content = "시스템 템플릿 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefault = false
        )
        val customTemplate = Template(
            id = "custom1",
            studentId = studentId,
            title = "나만의 템플릿",
            content = "커스텀 템플릿 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        
        every { 
            templateRepository.findByStudentIdOrType(studentId, TemplateOwnershipType.SYSTEM) 
        } returns listOf(systemTemplate, customTemplate)

        // when
        val result = service.getTemplates(studentId)

        // then
        assertThat(result).hasSize(2)
        assertThat(result).contains(systemTemplate, customTemplate)
        verify { templateRepository.findByStudentIdOrType(studentId, TemplateOwnershipType.SYSTEM) }
    }

    @Test
    @DisplayName("특정 템플릿을 조회한다")
    fun `템플릿 조회`() {
        // given
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "나만의 템플릿",
            content = "템플릿 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        every { templateRepository.findById(templateId) } returns Optional.of(template)

        // when
        val result = service.getTemplate(templateId)

        // then
        assertThat(result).isEqualTo(template)
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("템플릿을 찾을 수 없으면 예외를 발생시킨다")
    fun `템플릿 조회 실패 - 찾을 수 없음`() {
        // given
        every { templateRepository.findById(templateId) } returns Optional.empty()

        // when & then
        assertThatThrownBy { service.getTemplate(templateId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_NOT_FOUND)
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("커스텀 템플릿을 생성한다")
    fun `템플릿 생성`() {
        // given
        val title = "나만의 템플릿"
        val content = "템플릿 내용"
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = title,
            content = content,
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        
        every { templateRepository.save(any<Template>()) } returns template

        // when
        val result = service.createTemplate(studentId, title, content)

        // then
        assertThat(result).isEqualTo(template)
        assertThat(result.studentId).isEqualTo(studentId)
        assertThat(result.title).isEqualTo(title)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.type).isEqualTo(TemplateOwnershipType.CUSTOM)
        assertThat(result.isDefault).isFalse()
        verify { templateRepository.save(any<Template>()) }
    }

    @Test
    @DisplayName("템플릿을 수정한다")
    fun `템플릿 수정`() {
        // given
        val existingTemplate = Template(
            id = templateId,
            studentId = studentId,
            title = "기존 제목",
            content = "기존 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        val newTitle = "새 제목"
        val newContent = "새 내용"
        
        every { templateRepository.findById(templateId) } returns Optional.of(existingTemplate)
        every { templateRepository.save(any<Template>()) } answers { firstArg() }

        // when
        val result = service.updateTemplate(templateId, studentId, newTitle, newContent)

        // then
        assertThat(result.title).isEqualTo(newTitle)
        assertThat(result.content).isEqualTo(newContent)
        verify { templateRepository.findById(templateId) }
        verify { templateRepository.save(any<Template>()) }
    }

    @Test
    @DisplayName("시스템 템플릿은 수정할 수 없다")
    fun `템플릿 수정 실패 - 시스템 템플릿`() {
        // given
        val systemTemplate = Template(
            id = templateId,
            studentId = null,
            title = "시스템 템플릿",
            content = "시스템 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefault = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(systemTemplate)

        // when & then
        assertThatThrownBy { 
            service.updateTemplate(templateId, studentId, "새 제목", "새 내용") 
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("시스템 템플릿은 수정할 수 없습니다")
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("소유자가 아닌 경우 템플릿을 수정할 수 없다")
    fun `템플릿 수정 실패 - 소유자가 아님`() {
        // given
        val otherStudentId = "student2"
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "나만의 템플릿",
            content = "템플릿 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)

        // when & then
        assertThatThrownBy { 
            service.updateTemplate(templateId, otherStudentId, "새 제목", "새 내용") 
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("템플릿 소유자가 아닙니다")
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("템플릿을 삭제한다")
    fun `템플릿 삭제`() {
        // given
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "나만의 템플릿",
            content = "템플릿 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { templateRepository.delete(any<Template>()) } returns Unit

        // when
        service.deleteTemplate(templateId, studentId)

        // then
        verify { templateRepository.findById(templateId) }
        verify { templateRepository.delete(template) }
    }

    @Test
    @DisplayName("시스템 템플릿은 삭제할 수 없다")
    fun `템플릿 삭제 실패 - 시스템 템플릿`() {
        // given
        val systemTemplate = Template(
            id = templateId,
            studentId = null,
            title = "시스템 템플릿",
            content = "시스템 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefault = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(systemTemplate)

        // when & then
        assertThatThrownBy { service.deleteTemplate(templateId, studentId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_CANNOT_DELETE_SYSTEM)
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("템플릿을 기본값으로 설정한다")
    fun `템플릿 기본값 설정`() {
        // given
        val existingDefaultTemplate = Template(
            id = "existingDefault",
            studentId = studentId,
            title = "기존 기본 템플릿",
            content = "기존 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = true
        )
        val newDefaultTemplate = Template(
            id = templateId,
            studentId = studentId,
            title = "새 기본 템플릿",
            content = "새 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(newDefaultTemplate)
        every { 
            templateRepository.findAllByStudentIdAndIsDefaultTrue(studentId) 
        } returns listOf(existingDefaultTemplate)
        every { templateRepository.save(any<Template>()) } answers { firstArg() }

        // when
        val result = service.setAsDefault(templateId, studentId)

        // then
        assertThat(result.isDefault).isTrue()
        verify { templateRepository.findById(templateId) }
        verify { templateRepository.findAllByStudentIdAndIsDefaultTrue(studentId) }
        verify(exactly = 2) { templateRepository.save(any<Template>()) }
    }

    @Test
    @DisplayName("템플릿을 렌더링한다")
    fun `템플릿 렌더링`() {
        // given
        val problemId = 1000L
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "템플릿",
            content = """
                문제 ID: {{problemId}}
                제목: {{problemTitle}}
                티어: {{tier}}
                언어: {{language}}
                링크: {{link}}
            """.trimIndent(),
            type = TemplateOwnershipType.CUSTOM,
            isDefault = false
        )
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            language = "ko"
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { problemService.getProblemDetail(problemId) } returns problem

        // when
        val result = service.renderTemplate(templateId, problemId)

        // then
        assertThat(result).contains("문제 ID: 1000")
        assertThat(result).contains("제목: A+B")
        assertThat(result).contains("티어: BRONZE")
        assertThat(result).contains("언어: ko")
        assertThat(result).contains("링크: https://www.acmicpc.net/problem/1000")
        verify { templateRepository.findById(templateId) }
        verify { problemService.getProblemDetail(problemId) }
    }

    @Test
    @DisplayName("템플릿을 미리보기로 렌더링한다")
    fun `템플릿 미리보기 렌더링`() {
        // given
        val problemId = 1000L
        val templateContent = """
            문제 ID: {{problemId}}
            제목: {{problemTitle}}
            티어: {{tier}}
            언어: {{language}}
            링크: {{link}}
        """.trimIndent()
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            language = "ko"
        )
        
        every { problemService.getProblemDetail(problemId) } returns problem

        // when
        val result = service.previewTemplate(templateContent, problemId)

        // then
        assertThat(result).contains("문제 ID: 1000")
        assertThat(result).contains("제목: A+B")
        assertThat(result).contains("티어: BRONZE")
        assertThat(result).contains("언어: ko")
        assertThat(result).contains("링크: https://www.acmicpc.net/problem/1000")
        verify { problemService.getProblemDetail(problemId) }
        verify(exactly = 0) { templateRepository.findById(any()) }
    }
}
