package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
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
    private val studentRepository: StudentRepository = mockk()
    private val service = TemplateService(templateRepository, problemService, studentRepository)

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
            isDefaultSuccess = true,
            isDefaultFail = false
        )
        val customTemplate = Template(
            id = "custom1",
            studentId = studentId,
            title = "나만의 템플릿",
            content = "커스텀 템플릿 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
        assertThat(result.isDefaultSuccess).isFalse()
        assertThat(result.isDefaultFail).isFalse()
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
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
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(systemTemplate)

        // when & then
        assertThatThrownBy { service.deleteTemplate(templateId, studentId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_CANNOT_DELETE_SYSTEM)
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("템플릿을 성공용 기본값으로 설정한다")
    fun `템플릿 성공용 기본값 설정`() {
        // given
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "새 기본 템플릿",
            content = "새 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = service.setDefaultTemplate(templateId, TemplateCategory.SUCCESS, studentId)

        // then
        assertThat(result).isEqualTo(template)
        verify { templateRepository.findById(templateId) }
        verify { studentRepository.findById(studentId) }
        verify { 
            studentRepository.save(match { 
                it.id == studentId && it.defaultSuccessTemplateId == templateId 
            }) 
        }
    }

    @Test
    @DisplayName("템플릿을 실패용 기본값으로 설정한다")
    fun `템플릿 실패용 기본값 설정`() {
        // given
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "새 기본 템플릿",
            content = "새 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = service.setDefaultTemplate(templateId, TemplateCategory.FAIL, studentId)

        // then
        assertThat(result).isEqualTo(template)
        verify { templateRepository.findById(templateId) }
        verify { studentRepository.findById(studentId) }
        verify { 
            studentRepository.save(match { 
                it.id == studentId && it.defaultFailTemplateId == templateId 
            }) 
        }
    }

    @Test
    @DisplayName("시스템 템플릿도 기본값으로 설정할 수 있다")
    fun `템플릿 기본값 설정 - 시스템 템플릿`() {
        // given
        val systemTemplate = Template(
            id = templateId,
            studentId = null,
            title = "Simple(요약)",
            content = "시스템 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(systemTemplate)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = service.setDefaultTemplate(templateId, TemplateCategory.SUCCESS, studentId)

        // then
        assertThat(result).isEqualTo(systemTemplate)
        verify { templateRepository.findById(templateId) }
        verify { studentRepository.findById(studentId) }
        verify { 
            studentRepository.save(match { 
                it.id == studentId && it.defaultSuccessTemplateId == templateId 
            }) 
        }
    }

    @Test
    @DisplayName("기본 템플릿을 조회한다 - 사용자 기본 템플릿이 있는 경우")
    fun `기본 템플릿 조회 - 사용자 기본 템플릿`() {
        // given
        val userDefaultTemplate = Template(
            id = templateId,
            studentId = studentId,
            title = "사용자 기본 템플릿",
            content = "기본 내용",
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE,
            defaultSuccessTemplateId = templateId
        )
        
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { templateRepository.findById(templateId) } returns Optional.of(userDefaultTemplate)

        // when
        val result = service.getDefaultTemplate(TemplateCategory.SUCCESS, studentId)

        // then
        assertThat(result).isEqualTo(userDefaultTemplate)
        verify { studentRepository.findById(studentId) }
        verify { templateRepository.findById(templateId) }
    }

    @Test
    @DisplayName("기본 템플릿을 조회한다 - 시스템 기본 템플릿을 fallback으로 사용")
    fun `기본 템플릿 조회 - 시스템 기본 템플릿`() {
        // given
        val systemTemplate = Template(
            id = "system1",
            studentId = null,
            title = "Simple(요약)",
            content = "시스템 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE,
            defaultSuccessTemplateId = null
        )
        
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { 
            templateRepository.findByType(TemplateOwnershipType.SYSTEM) 
        } returns listOf(systemTemplate)

        // when
        val result = service.getDefaultTemplate(TemplateCategory.SUCCESS, studentId)

        // then
        assertThat(result).isEqualTo(systemTemplate)
        verify { studentRepository.findById(studentId) }
        verify { templateRepository.findByType(TemplateOwnershipType.SYSTEM) }
    }

    @Test
    @DisplayName("실패용 기본 템플릿을 조회한다 - 시스템 기본 템플릿을 fallback으로 사용")
    fun `실패용 기본 템플릿 조회 - 시스템 기본 템플릿`() {
        // given
        val systemTemplate = Template(
            id = "system1",
            studentId = null,
            title = "Detail(상세)",
            content = "시스템 내용",
            type = TemplateOwnershipType.SYSTEM,
            isDefaultSuccess = false,
            isDefaultFail = false
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE,
            defaultFailTemplateId = null
        )
        
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { 
            templateRepository.findByType(TemplateOwnershipType.SYSTEM) 
        } returns listOf(systemTemplate)

        // when
        val result = service.getDefaultTemplate(TemplateCategory.FAIL, studentId)

        // then
        assertThat(result).isEqualTo(systemTemplate)
        verify { studentRepository.findById(studentId) }
        verify { templateRepository.findByType(TemplateOwnershipType.SYSTEM) }
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
                소요 시간: {{timeTaken}}
            """.trimIndent(),
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
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
        val solution = Solution(
            problemId = ProblemId("1000"),
            timeTaken = TimeTakenSeconds(194L),
            result = ProblemResult.SUCCESS
        )
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE,
            solutions = Solutions().apply { add(solution) }
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { problemService.getProblemDetail(problemId) } returns problem
        every { studentRepository.findById(studentId) } returns Optional.of(student)

        // when
        val result = service.renderTemplate(templateId, problemId, studentId)

        // then
        assertThat(result).contains("문제 ID: 1000")
        assertThat(result).contains("제목: A+B")
        assertThat(result).contains("티어: BRONZE")
        assertThat(result).contains("언어: KO")
        assertThat(result).contains("링크: https://www.acmicpc.net/problem/1000")
        assertThat(result).contains("소요 시간: 3분 14초")
        verify { templateRepository.findById(templateId) }
        verify { problemService.getProblemDetail(problemId) }
        verify { studentRepository.findById(studentId) }
    }

    @Test
    @DisplayName("템플릿을 렌더링한다 - 풀이 기록이 없는 경우")
    fun `템플릿 렌더링 - 풀이 기록 없음`() {
        // given
        val problemId = 1000L
        val template = Template(
            id = templateId,
            studentId = studentId,
            title = "템플릿",
            content = """
                문제 ID: {{problemId}}
                소요 시간: {{timeTaken}}
            """.trimIndent(),
            type = TemplateOwnershipType.CUSTOM,
            isDefaultSuccess = false,
            isDefaultFail = false
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
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            currentTier = Tier.BRONZE,
            solutions = Solutions()
        )
        
        every { templateRepository.findById(templateId) } returns Optional.of(template)
        every { problemService.getProblemDetail(problemId) } returns problem
        every { studentRepository.findById(studentId) } returns Optional.of(student)

        // when
        val result = service.renderTemplate(templateId, problemId, studentId)

        // then
        assertThat(result).contains("문제 ID: 1000")
        assertThat(result).contains("소요 시간: -")
        verify { templateRepository.findById(templateId) }
        verify { problemService.getProblemDetail(problemId) }
        verify { studentRepository.findById(studentId) }
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
        assertThat(result).contains("언어: KO")
        assertThat(result).contains("링크: https://www.acmicpc.net/problem/1000")
        verify { problemService.getProblemDetail(problemId) }
        verify(exactly = 0) { templateRepository.findById(any()) }
    }

    @Test
    @DisplayName("코드 블록 내의 {{language}}는 프로그래밍 언어로 치환된다")
    fun `코드 블록 언어 태그 치환`() {
        // given
        val problemId = 1000L
        val templateContent = """
            # {{problemTitle}}
            
            ## 제출한 코드
            
            ```{{language}}
            fun main() {
                println("Hello")
            }
            ```
            
            문제 언어: {{language}}
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
        val result = service.previewTemplate(templateContent, problemId, "KOTLIN")

        // then
        assertThat(result).contains("```kotlin")
        assertThat(result).contains("문제 언어: KO")
        assertThat(result).doesNotContain("```KO")
        verify { problemService.getProblemDetail(problemId) }
    }

    @Test
    @DisplayName("프로그래밍 언어가 없으면 코드 블록은 text로 치환된다")
    fun `코드 블록 기본값`() {
        // given
        val problemId = 1000L
        val templateContent = """
            ```{{language}}
            코드
            ```
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
        val result = service.previewTemplate(templateContent, problemId, null)

        // then
        assertThat(result).contains("```text")
        verify { problemService.getProblemDetail(problemId) }
    }

    @Test
    @DisplayName("유효하지 않은 프로그래밍 언어 코드는 text로 치환된다")
    fun `유효하지 않은 언어 코드 처리`() {
        // given
        val problemId = 1000L
        val templateContent = """
            ```{{language}}
            코드
            ```
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
        val result = service.previewTemplate(templateContent, problemId, "INVALID_LANG")

        // then
        assertThat(result).contains("```text")
        verify { problemService.getProblemDetail(problemId) }
    }
}
