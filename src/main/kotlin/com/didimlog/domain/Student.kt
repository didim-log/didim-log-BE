package com.didimlog.domain

import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 알고리즘 학습자의 상태와 풀이 기록을 관리하는 Aggregate Root
 * 티어 정보는 Solved.ac API를 통해 외부에서 동기화되며, 자체 승급 로직은 사용하지 않는다.
 */
@Document(collection = "students")
data class Student(
    @Id
    val id: String? = null,
    val nickname: Nickname,
    val bojId: BojId,
    val currentTier: Tier,
    val solutions: Solutions = Solutions()
) {

    /**
     * 문제 풀이 결과를 기록한다.
     * Solved.ac를 Source of Truth로 사용하므로, 자동 승급 로직은 포함하지 않는다.
     *
     * @param problem 풀이한 문제
     * @param timeTakenSeconds 풀이에 소요된 시간 (초)
     * @param isSuccess 풀이 성공 여부
     */
    fun solveProblem(problem: Problem, timeTakenSeconds: TimeTakenSeconds, isSuccess: Boolean): Student {
        val result = toProblemResult(isSuccess)
        val newSolution = Solution(
            problemId = problem.id,
            timeTaken = timeTakenSeconds,
            result = result
        )
        val updatedSolutions = Solutions().apply {
            solutions.getAll().forEach { add(it) }
            add(newSolution)
        }
        return copy(solutions = updatedSolutions)
    }

    /**
     * 현재 티어를 반환한다.
     */
    fun tier(): Tier = currentTier

    /**
     * 외부(Solved.ac API)에서 가져온 티어 정보로 티어를 업데이트한다.
     * Solved.ac를 Source of Truth로 사용하므로, 이 메서드를 통해 티어를 동기화한다.
     *
     * @param newTier 새로운 티어 (Solved.ac에서 가져온 정보)
     * @return 티어가 업데이트된 새로운 Student 인스턴스
     */
    fun updateTier(newTier: Tier): Student {
        return copy(currentTier = newTier)
    }

    /**
     * @deprecated Solved.ac 동기화 방식으로 변경되어 사용하지 않음. updateTier를 사용하세요.
     */
    @Deprecated("Solved.ac 동기화 방식으로 변경되어 사용하지 않음. updateTier를 사용하세요.")
    fun syncTier(targetTier: Tier): Student {
        return updateTier(targetTier)
    }

    /**
     * 풀이한 문제 ID 목록을 반환한다.
     */
    fun getSolvedProblemIds(): Set<ProblemId> {
        return solutions.getAll().map { it.problemId }.toSet()
    }

    private fun toProblemResult(isSuccess: Boolean): ProblemResult {
        if (isSuccess) {
            return ProblemResult.SUCCESS
        }
        return ProblemResult.FAIL
    }
}


