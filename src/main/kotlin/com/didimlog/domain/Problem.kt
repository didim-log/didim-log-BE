package com.didimlog.domain

import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 알고리즘 문제 정보를 표현하는 도메인 객체
 * 불변 객체로 설계하여 생성 시점 이후 상태가 변경되지 않는다.
 *
 * @property level Solved.ac 기준 난이도 레벨 (1~30)
 */
@Document(collection = "problems")
data class Problem(
    @Id
    val id: ProblemId,
    val title: String,
    val category: String,
    val difficulty: Tier,
    val level: Int,
    val url: String
) {
    init {
        require(level in 1..30) { "난이도 레벨은 1~30 사이여야 합니다. level=$level" }
    }

    /**
     * Solved.ac 레벨을 반환한다. (기존 difficultyLevel과의 호환성을 위해 유지)
     */
    val difficultyLevel: Int
        get() = level

    /**
     * 주어진 티어보다 이 문제가 더 어려운지 여부를 판단한다.
     * Solved.ac 레벨을 직접 비교하여 단순하게 판단한다.
     *
     * @param tier 비교 대상 티어
     * @return 문제가 더 어렵다면 true, 그렇지 않다면 false
     */
    fun isHarderThan(tier: Tier): Boolean {
        return level > tier.value
    }
}


