package com.didimlog.application.retrospective

import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회고 관리 서비스
 * 학생이 문제 풀이 후 작성하는 회고를 관리하고, 템플릿을 생성한다.
 */
@Service
class RetrospectiveService(
    private val retrospectiveRepository: RetrospectiveRepository,
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository,
    private val studentLifecycleCoordinator: StudentLifecycleCoordinator
) {

    /**
     * 회고를 작성하거나 수정한다.
     * 이미 해당 문제에 대한 회고가 있으면 수정하고, 없으면 새로 작성한다.
     *
     * @param studentId Student 엔티티의 DB ID (@Id 필드)
     * @param problemId 문제 ID
     * @param content 회고 내용
     * @param summary 한 줄 요약 (필수)
     * @param solutionResult 풀이 결과 (SUCCESS/FAIL, 선택사항)
     * @param solvedCategory 사용자가 선택한 풀이 전략 태그 (선택사항)
     * @return 저장된 회고
     * @throws IllegalArgumentException 학생이나 문제를 찾을 수 없는 경우
     */
    @Transactional
    fun writeRetrospective(
        studentId: String,
        problemId: String,
        content: String,
        summary: String,
        solutionResult: com.didimlog.domain.enums.ProblemResult? = null,
        solvedCategory: String? = null,
        solveTime: String? = null
    ): Retrospective {
        return studentLifecycleCoordinator.execute(studentId) {
            val student = getStudent(studentId)
            val problem = findProblemOrThrow(problemId)

            val existingRetrospective = retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)
            existingRetrospective?.let { validateOwnerOrThrow(it, student) }
            val candidate = (existingRetrospective ?: Retrospective(
                studentId = studentId,
                problemId = problemId,
                content = content,
                mainCategory = problem.category
            ))
                .updateContent(content, summary)
                .updateSolutionInfo(solutionResult, solvedCategory, solveTime)
                .copy(mainCategory = problem.category)

            if (existingRetrospective != null) {
                retrospectiveRepository.updateEditableFieldsByIdAndStudent(candidate)
                    ?: throw BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT)
            } else {
                upsertRetrospective(candidate)
            }
        }
    }

    /**
     * 회고를 조회한다.
     *
     * @param retrospectiveId 회고 ID
     * @return 회고
     * @throws IllegalArgumentException 회고를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getRetrospective(retrospectiveId: String, studentId: String): Retrospective {
        val retrospective = findRetrospectiveOrThrow(retrospectiveId)
        val student = getStudent(studentId)
        validateOwnerOrThrow(retrospective, student)
        return retrospective
    }

    /**
     * 회고를 수정한다.
     * 소유권 검증을 수행한다.
     *
     * @param retrospectiveId 회고 ID
     * @param studentId 수정을 시도하는 학생 ID (보안 검증용)
     * @param content 회고 내용
     * @param summary 한 줄 요약 (필수)
     * @param solutionResult 풀이 결과 (선택사항)
     * @param solvedCategory 사용자가 선택한 풀이 전략 태그 (선택사항)
     * @param solveTime 풀이 소요 시간 (선택사항)
     * @return 수정된 회고
     * @throws BusinessException 회고를 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun updateRetrospective(
        retrospectiveId: String,
        studentId: String,
        content: String,
        summary: String,
        solutionResult: com.didimlog.domain.enums.ProblemResult? = null,
        solvedCategory: String? = null,
        solveTime: String? = null
    ): Retrospective {
        return studentLifecycleCoordinator.execute(studentId) {
            val retrospective = getRetrospective(retrospectiveId, studentId)
            val mainCategory = retrospective.mainCategory
                ?: problemRepository.findById(retrospective.problemId)
                    .map { it.category }
                    .orElse(null)

            val updatedRetrospective = retrospective
                .updateContent(content, summary)
                .updateSolutionInfo(solutionResult, solvedCategory, solveTime)
                .copy(mainCategory = mainCategory)

            retrospectiveRepository.updateEditableFieldsByIdAndStudent(updatedRetrospective)
                ?: throw BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT)
        }
    }

    /**
     * 회고를 삭제한다.
     * 소유권 검증을 수행하고, 회고 삭제 시 해당 문제의 풀이 기록(Solution)도 함께 삭제한다.
     *
     * @param retrospectiveId 회고 ID
     * @param studentId 삭제를 시도하는 학생 ID (보안 검증용)
     * @throws BusinessException 회고를 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun deleteRetrospective(retrospectiveId: String, studentId: String): Retrospective {
        return studentLifecycleCoordinator.execute(studentId) {
            val retrospective = findRetrospectiveOrThrow(retrospectiveId)
            val student = getStudent(studentId)
            validateOwnerOrThrow(retrospective, student)

            // 회고 삭제 시 해당 문제의 풀이 기록(Solution)도 함께 삭제
            val problemId = ProblemId(retrospective.problemId)
            removeSolutionsWithRetry(student, problemId)

            // 회고 삭제
            retrospectiveRepository.findAndRemoveByIdAndStudentId(retrospectiveId, studentId)
                ?: retrospective
        }
    }

    private fun validateOwnerOrThrow(retrospective: Retrospective, student: Student) {
        try {
            retrospective.validateOwner(student)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, e.message ?: ErrorCode.ACCESS_DENIED.message)
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
     * 검색 조건에 따라 회고 목록을 조회한다.
     *
     * @param condition 검색 조건
     * @param pageable 페이징 정보
     * @return 회고 페이지
     */
    @Transactional(readOnly = true)
    fun searchRetrospectives(condition: RetrospectiveSearchCondition, pageable: Pageable): Page<Retrospective> {
        return retrospectiveRepository.search(condition, pageable)
    }

    @Transactional
    fun toggleBookmark(retrospectiveId: String, studentId: String): Boolean {
        return studentLifecycleCoordinator.execute(studentId) {
            getRetrospective(retrospectiveId, studentId)
            val updatedRetrospective =
                retrospectiveRepository.toggleBookmarkByIdAndStudentId(retrospectiveId, studentId)
                    ?: throw BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT)
            updatedRetrospective.isBookmarked
        }
    }

    private fun upsertRetrospective(retrospective: Retrospective): Retrospective {
        return try {
            retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(retrospective)
        } catch (_: DuplicateKeyException) {
            try {
                retrospectiveRepository.upsertEditableFieldsByStudentAndProblem(retrospective)
            } catch (_: DuplicateKeyException) {
                throw BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT)
            }
        }
    }

    private fun removeSolutionsWithRetry(
        initialStudent: Student,
        problemId: ProblemId
    ) {
        var student = initialStudent
        val studentId = requireNotNull(student.id)

        repeat(MAX_CAS_RETRIES + 1) { attempt ->
            val documentVersion = requireNotNull(student.documentVersion) {
                "학생 문서 버전이 초기화되지 않았습니다. studentId=$studentId"
            }
            val updatedStudent = student.removeSolutionsByProblemId(problemId)
            studentRepository.updateStudyProgressById(
                studentId = studentId,
                expectedDocumentVersion = documentVersion,
                solutions = updatedStudent.solutions,
                consecutiveSolveDays = updatedStudent.consecutiveSolveDays,
                lastSolvedAt = updatedStudent.lastSolvedAt
            )?.let { return }

            if (attempt < MAX_CAS_RETRIES) {
                student = studentRepository.findById(studentId)
                    .orElseThrow { BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT) }
            }
        }

        throw BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT)
    }

    private fun findRetrospectiveOrThrow(retrospectiveId: String): Retrospective {
        return retrospectiveRepository.findById(retrospectiveId)
            .orElseThrow { BusinessException(ErrorCode.RETROSPECTIVE_NOT_FOUND, "회고를 찾을 수 없습니다. id=$retrospectiveId") }
    }

    private fun findProblemOrThrow(problemId: String): Problem {
        return problemRepository.findById(problemId)
            .orElseThrow { BusinessException(ErrorCode.PROBLEM_NOT_FOUND, "문제를 찾을 수 없습니다. id=$problemId") }
    }

    private companion object {
        const val MAX_CAS_RETRIES = 3
    }
}
