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
 * 티어 정보와 풀이 기록(Solutions)을 함께 보유하고, 티어 승급 규칙을 스스로 적용한다.
 */
@Document(collection = "students")
class Student(
    @Id
    val id: String? = null,
    val nickname: Nickname,
    val bojId: BojId,
    private var currentTier: Tier,
    private val solutions: Solutions = Solutions()
) {

    fun solveProblem(problem: Problem, timeTakenSeconds: TimeTakenSeconds, isSuccess: Boolean) {
        val result = toProblemResult(isSuccess)
        solutions.add(
            Solution(
                problemId = problem.id,
                timeTaken = timeTakenSeconds,
                result = result
            )
        )

        if (!isSuccess) {
            return
        }

        if (!canLevelUp()) {
            return
        }

        levelUp()
    }

    fun tier(): Tier = currentTier

    fun syncTier(targetTier: Tier) {
        currentTier = targetTier
    }

    fun getSolvedProblemIds(): Set<ProblemId> {
        return solutions.getAll().map { it.problemId }.toSet()
    }

    private fun toProblemResult(isSuccess: Boolean): ProblemResult {
        if (isSuccess) {
            return ProblemResult.SUCCESS
        }
        return ProblemResult.FAIL
    }

    private fun canLevelUp(): Boolean {
        val recentSuccessRate = solutions.calculateRecentSuccessRate()
        val canLevelUpByRate = recentSuccessRate >= 0.8
        val isNotMaxTier = currentTier.isNotMax()
        return canLevelUpByRate && isNotMaxTier
    }

    private fun levelUp() {
        currentTier = currentTier.next()
    }
}


