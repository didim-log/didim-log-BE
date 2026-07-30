package com.didimlog.application.log

import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Log
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
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

@DisplayName("LogService 테스트")
class LogServiceTest {

    private val logRepository: LogRepository = mockk()
    private val logFeedbackRepository: LogFeedbackRepository = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val studentLifecycleCoordinator = RecordingStudentLifecycleCoordinator()
    private val logService = LogService(
        logRepository,
        logFeedbackRepository,
        studentRepository,
        studentLifecycleCoordinator
    )

    @Test
    fun `createLog 는 content가 blank면 플레이스홀더로 저장한다`() {
        every { studentRepository.findById("student-1") } returns Optional.of(
            Student(
                id = "student-1",
                nickname = Nickname("tester1"),
                provider = Provider.BOJ,
                providerId = "tester1",
                bojId = BojId("latest1"),
                currentTier = Tier.BRONZE
            )
        )
        every { logRepository.save(any()) } answers { firstArg() }

        val saved = logService.createLog(
            title = "로그 제목",
            content = "   ",
            code = "print(1)",
            studentId = "student-1",
            bojId = "tester1",
            isSuccess = true
        )

        assertThat(saved.content.value).isEqualTo("(empty)")
        assertThat(saved.studentId).isEqualTo("student-1")
        assertThat(saved.bojId?.value).isEqualTo("latest1")
        assertThat(saved.isSuccess).isTrue()
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly("student-1")
        verify(exactly = 1) { logRepository.save(any()) }
    }

    @Test
    fun `createLog 는 잠금 안에서 학생이 사라졌으면 로그를 저장하지 않는다`() {
        every { studentRepository.findById("deleted-student") } returns Optional.empty()

        assertThatThrownBy {
            logService.createLog(
                title = "로그 제목",
                content = "로그 내용",
                code = "print(1)",
                studentId = "deleted-student"
            )
        }.isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.STUDENT_NOT_FOUND }

        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly("deleted-student")
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    fun `getLogTemplate 는 requesterStudentId가 비어있으면 UNAUTHORIZED`() {
        assertThatThrownBy {
            logService.getLogTemplate("log-1", "")
        }.isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.UNAUTHORIZED }
    }

    @Test
    fun `getLogTemplate 는 로그가 없으면 NOT_FOUND`() {
        every { logRepository.findById("log-1") } returns Optional.empty()

        assertThatThrownBy {
            logService.getLogTemplate("log-1", "tester1")
        }.isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.COMMON_RESOURCE_NOT_FOUND }
    }

    @Test
    fun `getLogTemplate 는 소유자가 다르면 ACCESS_DENIED`() {
        every { logRepository.findById("log-1") } returns Optional.of(
            log(id = "log-1", studentId = "owner-id", bojId = BojId("owner1"), content = "비밀 회고")
        )

        assertThatThrownBy {
            logService.getLogTemplate("log-1", "other-user")
        }.isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }
    }

    @Test
    fun `getLogTemplate 는 본인 로그면 content를 반환한다`() {
        every { logRepository.findById("log-1") } returns Optional.of(
            log(id = "log-1", studentId = "owner-id", bojId = BojId("owner1"), content = "내 회고 템플릿")
        )

        val template = logService.getLogTemplate("log-1", "owner-id")

        assertThat(template).isEqualTo("내 회고 템플릿")
    }

    private fun log(id: String, studentId: String?, bojId: BojId?, content: String): Log {
        return Log(
            id = id,
            title = LogTitle("title"),
            content = LogContent(content),
            code = LogCode("fun main() = Unit"),
            studentId = studentId,
            bojId = bojId
        )
    }

    private class RecordingStudentLifecycleCoordinator : StudentLifecycleCoordinator {
        val executedStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            executedStudentIds += studentId
            return action()
        }
    }
}
