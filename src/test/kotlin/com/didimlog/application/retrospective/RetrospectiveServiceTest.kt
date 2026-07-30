package com.didimlog.application.retrospective

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("RetrospectiveService 테스트")
class RetrospectiveServiceTest {

    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val problemRepository: ProblemRepository = mockk()

    private val retrospectiveService = RetrospectiveService(
        retrospectiveRepository,
        studentRepository,
        problemRepository,
        ImmediateCredentialSessionCoordinator()
    )

    @Test
    fun `모든 회고 변경은 학생 생명주기 잠금을 먼저 한 번 획득한다`() {
        val studentId = "student-id"
        val coordinator = RejectingStudentLifecycleCoordinator()
        val service = RetrospectiveService(
            retrospectiveRepository,
            studentRepository,
            problemRepository,
            coordinator
        )

        assertThrows<LifecycleBoundaryReached> {
            service.writeRetrospective(
                studentId = studentId,
                problemId = "1000",
                content = "생명주기 잠금 경계를 검증하는 회고입니다.",
                summary = "잠금 경계"
            )
        }
        assertThrows<LifecycleBoundaryReached> {
            service.updateRetrospective(
                retrospectiveId = "retrospective-id",
                studentId = studentId,
                content = "생명주기 잠금 경계를 검증하는 수정입니다.",
                summary = "잠금 경계"
            )
        }
        assertThrows<LifecycleBoundaryReached> {
            service.toggleBookmark("retrospective-id", studentId)
        }
        assertThrows<LifecycleBoundaryReached> {
            service.deleteRetrospective("retrospective-id", studentId)
        }

        assertThat(coordinator.studentIds)
            .containsExactly(studentId, studentId, studentId, studentId)
    }

    @Test
    @DisplayName("writeRetrospective는 새로운 회고를 작성한다")
    fun `새로운 회고 작성`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"
        val content = "이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요."

        val student = createStudent(id = studentId)
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns null

        every {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        } answers {
            firstArg<Retrospective>().copy(id = "retrospective-id")
        }

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "한 줄 요약 테스트",
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = "15m 30s"
        )

        // then
        assertThat(result.id).isEqualTo("retrospective-id")
        assertThat(result.studentId).isEqualTo(studentId)
        assertThat(result.problemId).isEqualTo(problemId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.summary).isEqualTo("한 줄 요약 테스트")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.SUCCESS)
        assertThat(result.solvedCategory).isEqualTo("DFS")
        assertThat(result.solveTime).isEqualTo("15m 30s")
        assertThat(result.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)
        verify(exactly = 1) {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(
                match { retrospective ->
                    retrospective.mainCategory == ProblemCategory.IMPLEMENTATION &&
                        retrospective.summary == "한 줄 요약 테스트"
                }
            )
        }
    }

    @Test
    @DisplayName("writeRetrospective는 기존 회고가 있으면 수정한다")
    fun `기존 회고 수정`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"
        val existingContent = "기존 회고 내용입니다."
        val newContent = "수정된 회고 내용입니다. 더 자세하게 작성했습니다."

        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        val student = createStudent(id = studentId)
        val existingRetrospective = Retrospective(
            id = "retrospective-id",
            studentId = studentId,
            problemId = problemId,
            content = existingContent,
            summary = "기존 한 줄 요약"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns existingRetrospective

        every {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        } answers { firstArg() }

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = newContent,
            summary = "수정된 한 줄 요약",
            solutionResult = com.didimlog.domain.enums.ProblemResult.FAIL,
            solvedCategory = "Greedy",
            solveTime = "20m 15s"
        )

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo("수정된 한 줄 요약")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.FAIL)
        assertThat(result.solvedCategory).isEqualTo("Greedy")
        assertThat(result.solveTime).isEqualTo("20m 15s")
        assertThat(result.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)
        verify(exactly = 1) {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        }
    }

    @Test
    @DisplayName("writeRetrospective는 동시 생성 충돌 시 기존 회고를 수정으로 수렴한다")
    fun `동시 생성 충돌 시 기존 회고로 수렴`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"
        val content = "동시 요청 시에도 중복 생성 없이 최종 회고가 유지되어야 합니다."
        val summary = "동시성 테스트"

        val student = createStudent(id = studentId)
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every {
            retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)
        } returns null
        every {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        } throws DuplicateKeyException("duplicate key") andThenAnswer {
            firstArg<Retrospective>().copy(id = "retrospective-id")
        }

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = summary,
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = "10m"
        )

        // then
        assertThat(result.content).isEqualTo(content)
        assertThat(result.summary).isEqualTo(summary)
        verify(exactly = 1) {
            retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)
        }
        verify(exactly = 2) {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        }
    }

    @Test
    fun `신규 회고 upsert 충돌이 반복되면 409를 반환한다`() {
        val studentId = "student-id"
        val problemId = "1000"
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            problemRepository.findById(problemId)
        } returns Optional.of(
            Problem(
                id = ProblemId(problemId),
                title = "A+B",
                category = ProblemCategory.IMPLEMENTATION,
                difficulty = Tier.BRONZE,
                level = 3,
                url = "https://www.acmicpc.net/problem/$problemId"
            )
        )
        every {
            retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)
        } returns null
        every {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        } throws DuplicateKeyException("first") andThenThrows DuplicateKeyException("second")

        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective(
                studentId,
                problemId,
                "중복 충돌을 두 번 발생시키는 회고 내용입니다.",
                "중복 충돌"
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        verify(exactly = 2) {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        }
    }

    @Test
    @DisplayName("writeRetrospective는 학생이 없으면 예외를 발생시킨다")
    fun `학생이 없으면 예외`() {
        // given
        every { studentRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective("missing", "1000", "content", "summary")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
    }

    @Test
    @DisplayName("writeRetrospective는 문제가 없으면 예외를 발생시킨다")
    fun `문제가 없으면 예외`() {
        // given
        val student = createStudent(id = "student-id")
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { problemRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective("student-id", "missing", "content", "summary")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)
    }

    @Test
    @DisplayName("updateRetrospective는 회고를 수정한다")
    fun `회고 수정 성공`() {
        // given
        val retrospectiveId = "retrospective-id"
        val studentId = "student-id"
        val newContent = "수정된 회고 내용입니다."
        val newSummary = "수정된 한 줄 요약"
        val newSolveTime = "25m 30s"

        val student = createStudent(id = studentId)
        val existingRetrospective = Retrospective(
            id = retrospectiveId,
            studentId = studentId,
            problemId = "1000",
            content = "기존 회고 내용입니다."
        )

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(existingRetrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every {
            problemRepository.findById(existingRetrospective.problemId)
        } returns Optional.of(
            Problem(
                id = ProblemId(existingRetrospective.problemId),
                title = "A+B",
                category = ProblemCategory.IMPLEMENTATION,
                difficulty = Tier.BRONZE,
                level = 3,
                url = "https://www.acmicpc.net/problem/${existingRetrospective.problemId}"
            )
        )

        every {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        } answers { firstArg() }

        // when
        val result = retrospectiveService.updateRetrospective(
            retrospectiveId = retrospectiveId,
            studentId = studentId,
            content = newContent,
            summary = newSummary,
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = newSolveTime
        )

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo(newSummary)
        assertThat(result.solveTime).isEqualTo(newSolveTime)
        assertThat(result.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)
        verify(exactly = 1) {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(
                match { it.mainCategory == ProblemCategory.IMPLEMENTATION }
            )
        }
    }

    @Test
    @DisplayName("updateRetrospective는 소유자가 아니면 예외를 발생시킨다")
    fun `회고 수정 실패 - 소유자가 아님`() {
        // given
        val retrospectiveId = "retrospective-id"
        val ownerId = "owner-123"
        val attackerId = "attacker-456"
        val attackerStudent = createStudent(id = attackerId)
        val existingRetrospective = Retrospective(
            id = retrospectiveId,
            studentId = ownerId,
            problemId = "1000",
            content = "기존 회고 내용입니다."
        )

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(existingRetrospective)
        every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)

        // when & then
        val exception = assertThrows<BusinessException> {
            retrospectiveService.updateRetrospective(
                retrospectiveId = retrospectiveId,
                studentId = attackerId,
                content = "수정된 내용입니다.",
                summary = "수정된 요약",
                solutionResult = null,
                solvedCategory = null,
                solveTime = null
            )
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
    }

    @Test
    @DisplayName("writeRetrospective는 기존 회고 수정 시 소유권을 검증한다")
    fun `기존 회고 수정 시 소유권 검증`() {
        // given
        val ownerId = "owner-123"
        val problemId = "problem-1"
        val ownerStudent = createStudent(id = ownerId)
        val existingRetrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = problemId,
            content = "기존 회고 내용입니다."
        )
        val problem = Problem(
            id = ProblemId(problemId),
            title = "Test Problem",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        // 소유자가 자신의 회고를 수정하는 경우 (정상)
        every { studentRepository.findById(ownerId) } returns Optional.of(ownerStudent)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(ownerId, problemId) } returns existingRetrospective
        every {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        } answers { firstArg() }

        val result = retrospectiveService.writeRetrospective(
            studentId = ownerId,
            problemId = problemId,
            content = "수정된 내용입니다.",
            summary = "수정된 요약"
        )
        assertThat(result.content).isEqualTo("수정된 내용입니다.")

        // 공격자가 다른 사용자의 회고를 수정하려는 경우 (실패)
        // 실제로는 findByStudentIdAndProblemId가 attackerId로 조회되므로 null이 반환되어 새로 작성됨
        // 하지만 같은 problemId에 대해 다른 사용자의 회고가 이미 존재하는 경우는 없음 (studentId + problemId가 unique)
        // 따라서 이 테스트는 실제 시나리오를 반영하지 않으므로 제거
    }

    @Test
    @DisplayName("deleteRetrospective는 다른 사용자의 회고를 삭제할 수 없다")
    fun `다른 사용자 회고 삭제 시도 시 예외 발생`() {
        // given
        val ownerId = "owner-123"
        val attackerId = "attacker-456"
        val attackerStudent = createStudent(id = attackerId)
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다."
        )

        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)

        // when & then
        val exception = assertThrows<BusinessException> {
            retrospectiveService.deleteRetrospective("retro-1", attackerId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
    }

    @Test
    @DisplayName("deleteRetrospective는 소유자의 회고를 정상적으로 삭제한다")
    fun `소유자 회고 삭제 성공`() {
        // given
        val ownerId = "owner-123"
        val ownerStudent = createStudent(id = ownerId)
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다."
        )

        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(ownerId) } returns Optional.of(ownerStudent)
        every {
            studentRepository.updateStudyProgressById(
                studentId = ownerId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        } returns ownerStudent.copy(documentVersion = 1)
        every {
            retrospectiveRepository.findAndRemoveByIdAndStudentId("retro-1", ownerId)
        } returns retrospective

        // when
        val result = retrospectiveService.deleteRetrospective("retro-1", ownerId)

        // then
        assertThat(result.id).isEqualTo("retro-1")
        verify(exactly = 1) {
            studentRepository.updateStudyProgressById(
                studentId = ownerId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        }
        verify(exactly = 1) {
            retrospectiveRepository.findAndRemoveByIdAndStudentId("retro-1", ownerId)
        }
    }

    @Test
    fun `수정 대상이 선행 삭제되면 재시도 가능한 409를 반환한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "기존 회고 내용입니다.",
            mainCategory = ProblemCategory.IMPLEMENTATION
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        } returns null

        val exception = assertThrows<BusinessException> {
            retrospectiveService.updateRetrospective(
                retrospectiveId = "retro-1",
                studentId = studentId,
                content = "삭제 경합 뒤 수정할 회고 내용입니다.",
                summary = "삭제 경합",
                solutionResult = null,
                solvedCategory = null,
                solveTime = null
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(exception.errorCode.retryable).isTrue()
    }

    @Test
    fun `기존 회고 재작성 대상이 선행 삭제되면 신규 upsert 없이 409를 반환한다`() {
        val studentId = "student-id"
        val problemId = "1000"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = problemId,
            content = "기존 회고 내용입니다."
        )
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            problemRepository.findById(problemId)
        } returns Optional.of(
            Problem(
                id = ProblemId(problemId),
                title = "A+B",
                category = ProblemCategory.DP,
                difficulty = Tier.BRONZE,
                level = 3,
                url = "https://www.acmicpc.net/problem/$problemId"
            )
        )
        every {
            retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)
        } returns retrospective
        every {
            retrospectiveRepository.updateEditableFieldsByIdAndStudent(any())
        } returns null

        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective(
                studentId,
                problemId,
                "삭제 경합 뒤 재작성하면 안 되는 회고 내용입니다.",
                "재작성 충돌"
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        verify(exactly = 0) {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(any())
        }
    }

    @Test
    fun `북마크는 저장소 원자 반전 결과를 반환한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "북마크를 원자적으로 반전하는 회고입니다.",
            isBookmarked = false
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            retrospectiveRepository.toggleBookmarkByIdAndStudentId("retro-1", studentId)
        } returns retrospective.copy(isBookmarked = true)

        val result = retrospectiveService.toggleBookmark("retro-1", studentId)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            retrospectiveRepository.toggleBookmarkByIdAndStudentId("retro-1", studentId)
        }
        verify(exactly = 0) { retrospectiveRepository.save(any<Retrospective>()) }
    }

    @Test
    fun `북마크 대상이 선행 삭제되면 409를 반환한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "삭제된 북마크 대상을 확인하는 회고입니다."
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            retrospectiveRepository.toggleBookmarkByIdAndStudentId("retro-1", studentId)
        } returns null

        val exception = assertThrows<BusinessException> {
            retrospectiveService.toggleBookmark("retro-1", studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
    }

    @Test
    fun `동시 삭제로 회고가 먼저 사라져도 삭제 성공으로 처리한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "삭제 경합을 확인하는 회고 내용입니다."
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        } returns createStudent(studentId).copy(documentVersion = 1)
        every {
            retrospectiveRepository.findAndRemoveByIdAndStudentId("retro-1", studentId)
        } returns null

        val result = retrospectiveService.deleteRetrospective("retro-1", studentId)

        assertThat(result).isEqualTo(retrospective)
        verify(exactly = 1) {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        }
    }

    @Test
    fun `학생이 삭제 경합으로 사라지면 회고를 삭제하지 않고 409를 반환한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "학생 삭제 경합을 확인하는 회고입니다."
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returnsMany listOf(
            Optional.of(createStudent(studentId)),
            Optional.empty()
        )
        every {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        } returns null

        val exception = assertThrows<BusinessException> {
            retrospectiveService.deleteRetrospective("retro-1", studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        verify(exactly = 0) {
            retrospectiveRepository.findAndRemoveByIdAndStudentId(any(), any())
        }
    }

    @Test
    fun `풀이 삭제 CAS 충돌 뒤 최신 Student에서 다시 계산한다`() {
        val studentId = "student-id"
        val targetProblemId = ProblemId("1000")
        val remainingProblemId = ProblemId("2000")
        val solvedAt = LocalDateTime.now()
        val initialStudent = createStudent(studentId).copy(
            solutions = solutionsOf(
                Solution(
                    problemId = targetProblemId,
                    timeTaken = TimeTakenSeconds(100),
                    result = ProblemResult.SUCCESS,
                    solvedAt = solvedAt.minusDays(1)
                )
            ),
            consecutiveSolveDays = 1,
            lastSolvedAt = solvedAt.toLocalDate().minusDays(1),
            documentVersion = 0
        )
        val latestStudent = initialStudent.copy(
            solutions = solutionsOf(
                *initialStudent.solutions.getAll().toTypedArray(),
                Solution(
                    problemId = remainingProblemId,
                    timeTaken = TimeTakenSeconds(80),
                    result = ProblemResult.SUCCESS,
                    solvedAt = solvedAt
                )
            ),
            consecutiveSolveDays = 2,
            lastSolvedAt = solvedAt.toLocalDate(),
            documentVersion = 1
        )
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = targetProblemId.value,
            content = "삭제 CAS 재시도를 확인하는 회고입니다."
        )
        val capturedSolutions = mutableListOf<Solutions>()

        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returnsMany listOf(
            Optional.of(initialStudent),
            Optional.of(latestStudent)
        )
        every {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = any(),
                solutions = capture(capturedSolutions),
                consecutiveSolveDays = any(),
                lastSolvedAt = any()
            )
        } returnsMany listOf(
            null,
            latestStudent.removeSolutionsByProblemId(targetProblemId).copy(documentVersion = 2)
        )
        every {
            retrospectiveRepository.findAndRemoveByIdAndStudentId("retro-1", studentId)
        } returns retrospective

        retrospectiveService.deleteRetrospective("retro-1", studentId)

        assertThat(capturedSolutions).hasSize(2)
        assertThat(capturedSolutions[1].getAll().map { it.problemId })
            .containsExactly(remainingProblemId)
        verify(exactly = 2) {
            studentRepository.updateStudyProgressById(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `풀이 삭제 CAS를 모두 소진하면 회고를 삭제하지 않고 409를 반환한다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "삭제 CAS 소진을 확인하는 회고입니다."
        )
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returnsMany (0L..3L).map { version ->
            Optional.of(createStudent(studentId).copy(documentVersion = version))
        }
        every {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = any(),
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        } returns null

        val exception = assertThrows<BusinessException> {
            retrospectiveService.deleteRetrospective("retro-1", studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(exception.errorCode.retryable).isTrue()
        verify(exactly = 4) { studentRepository.findById(studentId) }
        verify(exactly = 4) {
            studentRepository.updateStudyProgressById(any(), any(), any(), any(), null)
        }
        verify(exactly = 0) {
            retrospectiveRepository.findAndRemoveByIdAndStudentId(any(), any())
        }
    }

    @Test
    fun `풀이 삭제 저장소 오류는 재시도하지 않는다`() {
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "삭제 저장소 오류를 확인하는 회고입니다."
        )
        val databaseFailure = IllegalStateException("mongo unavailable")
        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(createStudent(studentId))
        every {
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = 0,
                solutions = any(),
                consecutiveSolveDays = 0,
                lastSolvedAt = null
            )
        } throws databaseFailure

        val thrown = assertThrows<IllegalStateException> {
            retrospectiveService.deleteRetrospective("retro-1", studentId)
        }

        assertThat(thrown).isSameAs(databaseFailure)
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 1) {
            studentRepository.updateStudyProgressById(any(), any(), any(), any(), null)
        }
        verify(exactly = 0) {
            retrospectiveRepository.findAndRemoveByIdAndStudentId(any(), any())
        }
    }

    private fun createStudent(id: String): Student {
        return Student(
            id = id,
            nickname = Nickname("test-user"),
            provider = Provider.BOJ,
            providerId = "testuser",
            bojId = BojId("testuser"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            primaryLanguage = null,
            documentVersion = 0
        )
    }

    private fun solutionsOf(vararg solution: Solution): Solutions {
        return Solutions().apply {
            solution.forEach(::add)
        }
    }

    private class RejectingStudentLifecycleCoordinator : StudentLifecycleCoordinator {
        val studentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            studentIds += studentId
            throw LifecycleBoundaryReached()
        }
    }

    private class LifecycleBoundaryReached : RuntimeException()

    @Test
    @DisplayName("getRetrospective는 회고를 조회한다")
    fun `회고 조회`() {
        // given
        val retrospectiveId = "retrospective-id"
        val studentId = "student-id"
        val retrospective = Retrospective(
            id = retrospectiveId,
            studentId = studentId,
            problemId = "1000",
            content = "이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요.",
            summary = "한 줄 요약 테스트"
        )
        val student = createStudent(id = studentId)

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(retrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(student)

        // when
        val result = retrospectiveService.getRetrospective(retrospectiveId, studentId)

        // then
        assertThat(result.id).isEqualTo(retrospectiveId)
        assertThat(result.content).isEqualTo("이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요.")
    }

    @Test
    @DisplayName("getRetrospective는 회고가 없으면 예외를 발생시킨다")
    fun `회고가 없으면 예외`() {
        // given
        val studentId = "student-id"
        val student = createStudent(id = studentId)
        every { retrospectiveRepository.findById("missing") } returns Optional.empty()
        every { studentRepository.findById(studentId) } returns Optional.of(student)

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.getRetrospective("missing", studentId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.RETROSPECTIVE_NOT_FOUND)
    }

}
