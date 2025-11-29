package com.didimlog.domain

import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId

/**
 * 알고리즘 문제 정보를 표현하는 도메인 객체
 * 불변 객체로 설계하여 생성 시점 이후 상태가 변경되지 않는다.
 */
data class Problem(
    val id: ProblemId,
    val title: String,
    val category: String,
    val difficulty: Tier,
    val url: String
) {

    val difficultyLevel: Int
        get() = difficulty.level

    /**
     * 주어진 티어보다 이 문제가 더 어려운지 여부를 판단한다.
     *
     * @param tier 비교 대상 티어
     * @return 문제가 더 어렵다면 true, 그렇지 않다면 false
     */
    fun isHarderThan(tier: Tier): Boolean {
        return difficulty.level > tier.level
    }
}


