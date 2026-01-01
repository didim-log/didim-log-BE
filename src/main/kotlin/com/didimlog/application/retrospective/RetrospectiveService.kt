package com.didimlog.application.retrospective

import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
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
    private val problemRepository: ProblemRepository
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
        val student = getStudent(studentId)
        validateProblemExists(problemId)

        val existingRetrospective = retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)

        if (existingRetrospective != null) {
            validateOwnerOrThrow(existingRetrospective, student)
            val updatedRetrospective = existingRetrospective
                .updateContent(content, summary)
                .updateSolutionInfo(solutionResult, solvedCategory, solveTime)
            return retrospectiveRepository.save(updatedRetrospective)
        }

        val newRetrospective = Retrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = summary,
            solutionResult = solutionResult,
            solvedCategory = solvedCategory,
            solveTime = solveTime
        )
        return retrospectiveRepository.save(newRetrospective)
    }

    /**
     * 회고를 조회한다.
     *
     * @param retrospectiveId 회고 ID
     * @return 회고
     * @throws IllegalArgumentException 회고를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getRetrospective(retrospectiveId: String): Retrospective {
        return retrospectiveRepository.findById(retrospectiveId)
            .orElseThrow { BusinessException(ErrorCode.RETROSPECTIVE_NOT_FOUND, "회고를 찾을 수 없습니다. id=$retrospectiveId") }
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
        val retrospective = getRetrospective(retrospectiveId)
        val student = getStudent(studentId)

        validateOwnerOrThrow(retrospective, student)

        val updatedRetrospective = retrospective
            .updateContent(content, summary)
            .updateSolutionInfo(solutionResult, solvedCategory, solveTime)

        return retrospectiveRepository.save(updatedRetrospective)
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
        val retrospective = getRetrospective(retrospectiveId)
        val student = getStudent(studentId)

        validateOwnerOrThrow(retrospective, student)

        // 회고 삭제 시 해당 문제의 풀이 기록(Solution)도 함께 삭제
        val problemId = ProblemId(retrospective.problemId)
        val updatedStudent = student.removeSolutionByProblemId(problemId)
        studentRepository.save(updatedStudent)

        // 회고 삭제
        retrospectiveRepository.delete(retrospective)
        return retrospective
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

    /**
     * 회고의 북마크 상태를 토글한다.
     *
     * @param retrospectiveId 회고 ID
     * @return 변경된 북마크 상태
     * @throws IllegalArgumentException 회고를 찾을 수 없는 경우
     */
    @Transactional
    fun toggleBookmark(retrospectiveId: String): Boolean {
        val retrospective = retrospectiveRepository.findById(retrospectiveId)
            .orElseThrow { BusinessException(ErrorCode.RETROSPECTIVE_NOT_FOUND, "회고를 찾을 수 없습니다. id=$retrospectiveId") }
        
        val updatedRetrospective = retrospective.toggleBookmark()
        retrospectiveRepository.save(updatedRetrospective)
        
        return updatedRetrospective.isBookmarked
    }

    /**
     * 문제 정보를 바탕으로 회고 템플릿을 생성한다.
     * 마크다운 형식으로 제목, 문제 링크, 접근 방법, 코드 블록 등의 기본 구조를 제공한다.
     * 결과 타입(SUCCESS/FAIL)에 따라 다른 템플릿을 반환한다.
     *
     * @param problemId 문제 ID
     * @param resultType 풀이 결과 타입 (SUCCESS/FAIL)
     * @return 마크다운 형식의 템플릿 문자열
     * @throws IllegalArgumentException 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun generateTemplate(problemId: String, resultType: com.didimlog.domain.enums.ProblemResult): String {
        val problem = findProblemOrThrow(problemId)
        return buildTemplate(problem, resultType)
    }


    private fun validateProblemExists(problemId: String) {
        findProblemOrThrow(problemId)
    }

    private fun findProblemOrThrow(problemId: String): Problem {
        return problemRepository.findById(problemId)
            .orElseThrow { BusinessException(ErrorCode.PROBLEM_NOT_FOUND, "문제를 찾을 수 없습니다. id=$problemId") }
    }

    private fun buildTemplate(problem: Problem, resultType: com.didimlog.domain.enums.ProblemResult): String {
        return when (resultType) {
            com.didimlog.domain.enums.ProblemResult.SUCCESS -> buildSuccessTemplate(problem)
            com.didimlog.domain.enums.ProblemResult.FAIL -> buildFailTemplate(problem)
            com.didimlog.domain.enums.ProblemResult.TIME_OVER -> buildFailTemplate(problem)
        }
    }

    private fun buildSuccessTemplate(problem: Problem): String {
        val template = StringBuilder()
        template.appendLine("# 🏆 ${problem.title} 해결 회고")
        template.appendLine()
        template.appendLine("## 💡 핵심 접근 (Key Idea)")
        template.appendLine()
        template.appendLine("<!-- 여기에 문제 해결의 핵심 접근 방법을 작성하세요 -->")
        template.appendLine()
        template.appendLine("## ⏱️ 시간/공간 복잡도")
        template.appendLine()
        template.appendLine("<!-- 여기에 시간 복잡도와 공간 복잡도를 작성하세요 -->")
        template.appendLine()
        template.appendLine("## ✨ 개선할 점")
        template.appendLine()
        template.appendLine("<!-- 여기에 더 나은 풀이 방법이나 개선할 점을 작성하세요 -->")
        template.appendLine()

        return template.toString()
    }

    private fun buildFailTemplate(problem: Problem): String {
        val template = StringBuilder()
        template.appendLine("# 💥 ${problem.title} 오답 노트")
        template.appendLine()
        template.appendLine("## 🧐 실패 원인 (Why?)")
        template.appendLine()
        template.appendLine("<!-- 여기에 문제를 풀지 못한 원인을 작성하세요 -->")
        template.appendLine()
        template.appendLine("## 📚 부족했던 개념")
        template.appendLine()
        template.appendLine("<!-- 여기에 부족했던 알고리즘 개념이나 자료구조를 작성하세요 -->")
        template.appendLine()
        template.appendLine("## 🔧 다음 시도 계획")
        template.appendLine()
        template.appendLine("<!-- 여기에 다음에 다시 시도할 때의 계획을 작성하세요 -->")
        template.appendLine()

        return template.toString()
    }
}
