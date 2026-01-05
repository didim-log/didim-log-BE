package com.didimlog.application.study

import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StudyService(
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository
) {

    /**
     * 문제 풀이 결과를 제출한다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @param problemId 문제 ID
     * @param timeTaken 풀이 소요 시간 (초)
     * @param isSuccess 풀이 성공 여부
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional
    fun submitSolution(bojId: String, problemId: String, timeTaken: Long, isSuccess: Boolean) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }

        val problem = problemRepository.findById(problemId)
            .orElseThrow {
                BusinessException(ErrorCode.PROBLEM_NOT_FOUND, "문제를 찾을 수 없습니다. id=$problemId")
            }

        val timeTakenSeconds = TimeTakenSeconds(timeTaken)

        val updatedStudent = student.solveProblem(problem, timeTakenSeconds, isSuccess)
        studentRepository.save(updatedStudent)
    }
}
