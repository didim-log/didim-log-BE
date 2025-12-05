package com.didimlog.domain

import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import java.time.LocalDateTime

/**
 * 단일 문제 풀이 정보를 표현하는 도메인 객체
 * 문제 ID, 풀이 시간, 결과, 풀이 시각을 함께 보존한다.
 * 원시값 포장을 통해 타입 안정성과 도메인 규칙을 보장한다.
 */
data class Solution(
    val problemId: ProblemId,
    val timeTaken: TimeTakenSeconds,
    /**
     * 문제 풀이 결과 (SUCCESS/FAIL)
     * 사용자가 직접 선택한 결과임을 명시한다.
     */
    val result: ProblemResult,
    val solvedAt: LocalDateTime = LocalDateTime.now()
) {

    /**
     * 문제 풀이가 성공인지 여부를 반환한다.
     *
     * @return SUCCESS 결과이면 true, 아니면 false
     */
    fun isSuccess(): Boolean = result == ProblemResult.SUCCESS
}

