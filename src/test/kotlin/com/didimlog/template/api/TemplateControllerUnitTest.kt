package com.didimlog.template.api

import com.didimlog.template.application.TemplateService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.template.domain.TemplateCategory
import com.didimlog.template.domain.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.template.domain.SectionPreset
import com.didimlog.template.domain.Template
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.template.api.dto.TemplatePreviewRequest
import com.didimlog.template.api.dto.TemplateRenderRequest
import com.didimlog.template.api.dto.TemplateRenderResponse
import com.didimlog.template.api.dto.TemplateRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.LocalDateTime
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

@DisplayName("TemplateController 단위 테스트")
class TemplateControllerUnitTest {

    private val templateService: TemplateService = mockk()
    private val studentRepository: StudentRepository = mockk()

    private val studentId = "student-1"
    private val authentication = UsernamePasswordAuthenticationToken(studentId, null, emptyList())
    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val student = Student(
        id = studentId,
        nickname = Nickname("tester"),
        provider = Provider.BOJ,
        providerId = studentId,
        currentTier = Tier.BRONZE,
        role = Role.USER,
        defaultSuccessTemplateId = "custom-success",
        defaultFailTemplateId = null
    )

    private fun controller(timeoutMillis: Long = 2_000L, fallbackEnabled: Boolean = true) =
        TemplateController(templateService, studentRepository, timeoutMillis, fallbackEnabled)

    private fun template(id: String, type: TemplateOwnershipType = TemplateOwnershipType.CUSTOM) = Template(
        id = id,
        studentId = if (type == TemplateOwnershipType.CUSTOM) studentId else null,
        title = "템플릿 $id",
        content = "내용 $id",
        type = type,
        createdAt = now,
        updatedAt = now
    )

    @BeforeEach
    fun setUp() {
        every { studentRepository.findById(studentId) } returns Optional.of(student)
    }

    @Test
    @DisplayName("템플릿 목록은 서비스가 결정한 기본 템플릿 ID로 기본 여부를 표시한다")
    fun `템플릿 목록 기본 표시`() {
        val templates = listOf(template("custom-success"), template("system-fail", TemplateOwnershipType.SYSTEM))
        every { templateService.getTemplates(studentId) } returns templates
        every {
            templateService.resolveDefaultTemplateIds(student, setOf("custom-success", "system-fail"))
        } returns ("custom-success" to "system-fail")

        val body = controller().getTemplates(authentication).body!!

        assertThat(body.map { Triple(it.id, it.isDefaultSuccess, it.isDefaultFail) }).containsExactly(
            Triple("custom-success", true, false),
            Triple("system-fail", false, true)
        )
        assertThat(body[1].type).isEqualTo("SYSTEM")
        assertThat(body[0].content).isEqualTo("내용 custom-success")
    }

    @Test
    @DisplayName("템플릿 요약 목록은 기본 템플릿이 없으면 어떤 항목도 기본으로 표시하지 않는다")
    fun `템플릿 요약 목록 기본 표시`() {
        every { templateService.getTemplateSummaries(studentId) } returns listOf(
            TemplateService.TemplateSummaryProjection("a", studentId, "A", "CUSTOM", now, now),
            TemplateService.TemplateSummaryProjection("b", null, "B", "SYSTEM", now, now)
        )
        every { templateService.resolveDefaultTemplateIds(student, setOf("a", "b")) } returns (null to "b")

        val body = controller().getTemplateSummaries(authentication).body!!

        assertThat(body.map { Triple(it.id, it.isDefaultSuccess, it.isDefaultFail) }).containsExactly(
            Triple("a", false, false),
            Triple("b", false, true)
        )
    }

    @Test
    @DisplayName("섹션 프리셋은 모든 프리셋을 카테고리 이름과 함께 반환한다")
    fun `섹션 프리셋 조회`() {
        val body = controller().getSectionPresets().body!!

        assertThat(body).hasSize(SectionPreset.values().size)
        assertThat(body.map { it.category }).containsAll(SectionPreset.values().map { it.category.name }.toSet())
    }

    @Test
    @DisplayName("미리보기는 요청 값을 그대로 서비스에 전달해 렌더링 결과를 반환한다")
    fun `템플릿 미리보기`() {
        every { templateService.previewTemplate("{{problemTitle}}", 1000L, "KOTLIN", "fun main() {}") } returns "A+B"

        val body = controller().previewTemplate(
            TemplatePreviewRequest(
                templateContent = "{{problemTitle}}",
                problemId = 1000L,
                programmingLanguage = "KOTLIN",
                code = "fun main() {}"
            )
        ).body!!

        assertThat(body).isEqualTo(TemplateRenderResponse(renderedContent = "A+B"))
    }

    @Test
    @DisplayName("템플릿 생성은 201과 함께 학생의 기본 템플릿 상태를 반영한 응답을 반환한다")
    fun `템플릿 생성`() {
        every { templateService.createTemplate(studentId, "제목", "내용") } returns template("custom-success")

        val response = controller().createTemplate(authentication, TemplateRequest("제목", "내용"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body!!.isDefaultSuccess).isTrue()
        assertThat(response.body!!.isDefaultFail).isFalse()
    }

    @Test
    @DisplayName("템플릿 수정 응답도 학생의 기본 템플릿 상태를 반영한다")
    fun `템플릿 수정`() {
        every { templateService.updateTemplate("other", studentId, "제목", "내용") } returns template("other")

        val body = controller().updateTemplate(authentication, "other", TemplateRequest("제목", "내용")).body!!

        assertThat(body.id).isEqualTo("other")
        assertThat(body.isDefaultSuccess).isFalse()
    }

    @Test
    @DisplayName("기본값 설정은 대소문자와 공백을 정규화한 카테고리로 처리하고 해당 카테고리만 새 ID로 표시한다")
    fun `기본 템플릿 설정`() {
        every {
            templateService.setDefaultTemplate("new-fail", TemplateCategory.FAIL, studentId)
        } returns template("new-fail")
        every {
            templateService.setDefaultTemplate("custom-success", TemplateCategory.SUCCESS, studentId)
        } returns template("custom-success")

        val fail = controller().setDefaultTemplate(authentication, "new-fail", " fail ").body!!
        val success = controller().setDefaultTemplate(authentication, "custom-success", "Success").body!!

        assertThat(fail.isDefaultFail).isTrue()
        assertThat(fail.isDefaultSuccess).isFalse()
        assertThat(success.isDefaultSuccess).isTrue()
        assertThat(success.isDefaultFail).isFalse()
    }

    @Test
    @DisplayName("잘못된 카테고리는 서비스 호출 없이 거절한다")
    fun `잘못된 카테고리`() {
        val exception = assertThrows<BusinessException> {
            controller().setDefaultTemplate(authentication, "t1", "MAYBE")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("category=MAYBE")
        verify(exactly = 0) { templateService.setDefaultTemplate(any(), any(), any()) }
    }

    @Test
    @DisplayName("기본 템플릿 조회는 조회한 카테고리의 템플릿을 기본으로 표시한다")
    fun `기본 템플릿 조회`() {
        every { templateService.getDefaultTemplate(TemplateCategory.FAIL, studentId) } returns
            template("system-fail", TemplateOwnershipType.SYSTEM)
        every { templateService.getDefaultTemplate(TemplateCategory.SUCCESS, studentId) } returns
            template("custom-success")

        val fail = controller().getDefaultTemplate(authentication, "FAIL").body!!
        val success = controller().getDefaultTemplate(authentication, "success").body!!

        assertThat(fail.id).isEqualTo("system-fail")
        assertThat(fail.isDefaultFail).isTrue()
        assertThat(success.isDefaultSuccess).isTrue()
    }

    @Test
    @DisplayName("인증된 학생이 없거나 저장된 ID가 없으면 STUDENT_NOT_FOUND로 거절한다")
    fun `학생 확인 실패`() {
        every { studentRepository.findById("ghost") } returns Optional.empty()
        every { studentRepository.findById("no-id") } returns Optional.of(student.copy(id = null))

        val missing = assertThrows<BusinessException> {
            controller().deleteTemplate(UsernamePasswordAuthenticationToken("ghost", null, emptyList()), "t1")
        }
        val noId = assertThrows<BusinessException> {
            controller().deleteTemplate(UsernamePasswordAuthenticationToken("no-id", null, emptyList()), "t1")
        }

        assertThat(missing.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(noId.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) { templateService.deleteTemplate(any(), any()) }
    }

    @Test
    @DisplayName("렌더링은 GET과 POST 모두 같은 서비스 호출로 결과를 반환한다")
    fun `템플릿 렌더링`() {
        every { templateService.renderTemplate("t1", 1000L, studentId, "JAVA", "code") } returns "렌더링 결과"

        val get = controller().renderTemplate(authentication, "t1", 1000L, "JAVA", "code").body!!
        val post = controller().renderTemplatePost(
            authentication,
            "t1",
            TemplateRenderRequest(problemId = 1000L, programmingLanguage = "JAVA", code = "code")
        ).body!!

        assertThat(get).isEqualTo(TemplateRenderResponse("렌더링 결과"))
        assertThat(post).isEqualTo(get)
    }

    @Test
    @DisplayName("렌더링 시간이 초과되면 사용자의 기본 템플릿으로 대체 렌더링한다")
    fun `렌더링 시간 초과 fallback`() {
        every { templateService.renderTemplate("slow", 1000L, studentId, null, null) } answers {
            Thread.sleep(1_000)
            "늦은 결과"
        }
        every {
            templateService.renderFallbackTemplate(
                problemId = 1000L,
                studentId = studentId,
                programmingLanguage = null,
                code = null
            )
        } returns "대체 결과"

        val body = controller(timeoutMillis = 50L).renderTemplate(authentication, "slow", 1000L, null, null).body!!

        assertThat(body).isEqualTo(
            TemplateRenderResponse(
                renderedContent = "대체 결과",
                fallbackUsed = true,
                fallbackReason = ErrorCode.TEMPLATE_RENDER_TIMEOUT.code
            )
        )
    }

    @Test
    @DisplayName("fallback이 꺼져 있으면 렌더링 시간 초과를 그대로 전달한다")
    fun `렌더링 시간 초과 fallback 비활성화`() {
        every { templateService.renderTemplate("slow", 1000L, studentId, null, null) } answers {
            Thread.sleep(1_000)
            "늦은 결과"
        }

        val exception = assertThrows<BusinessException> {
            controller(timeoutMillis = 50L, fallbackEnabled = false)
                .renderTemplate(authentication, "slow", 1000L, null, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.TEMPLATE_RENDER_TIMEOUT)
        verify(exactly = 0) { templateService.renderFallbackTemplate(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("렌더링 중 발생한 비즈니스 예외와 런타임 예외는 원래 예외로 전달하고 fallback하지 않는다")
    fun `렌더링 예외 전파`() {
        every { templateService.renderTemplate("denied", 1000L, studentId, null, null) } throws
            BusinessException(ErrorCode.ACCESS_DENIED)
        every { templateService.renderTemplate("broken", 1000L, studentId, null, null) } throws
            IllegalStateException("broken")

        val denied = assertThrows<BusinessException> {
            controller().renderTemplate(authentication, "denied", 1000L, null, null)
        }
        assertThatThrownBy { controller().renderTemplate(authentication, "broken", 1000L, null, null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("broken")

        assertThat(denied.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
        verify(exactly = 0) { templateService.renderFallbackTemplate(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("렌더링 중 검사 예외가 발생하면 내부 오류로 변환한다")
    fun `렌더링 검사 예외`() {
        every { templateService.renderTemplate("io", 1000L, studentId, null, null) } throws IOException("disk")

        val exception = assertThrows<BusinessException> {
            controller().renderTemplate(authentication, "io", 1000L, null, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
    }
}
