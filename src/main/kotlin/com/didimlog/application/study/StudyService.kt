package com.didimlog.application.study

import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StudyService(
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository
) {

    @Transactional
    fun submitSolution(studentId: String, problemId: String, timeTaken: Long, isSuccess: Boolean) {
        val student = studentRepository.findById(studentId)
            .orElseThrow { IllegalArgumentException("학생을 찾을 수 없습니다. id=$studentId") }

        val problemIdVo = ProblemId(problemId)
        val problem = problemRepository.findById(problemId)
            .orElseThrow { IllegalArgumentException("문제를 찾을 수 없습니다. id=$problemId") }

        val timeTakenSeconds = TimeTakenSeconds(timeTaken)

        val updatedStudent = student.solveProblem(problem, timeTakenSeconds, isSuccess)
        studentRepository.save(updatedStudent)
    }
}


