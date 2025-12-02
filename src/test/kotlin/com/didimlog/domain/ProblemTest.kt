package com.didimlog.domain

import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Problem 도메인 테스트")
class ProblemTest {

    @Test
    @DisplayName("문제의 난이도가 주어진 티어의 value보다 높으면 isHarderThan은 true를 반환한다")
    fun `난이도가 더 높으면 true 반환`() {
        // given: 레벨 11 문제는 SILVER 티어(value=8)보다 어렵다
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.GOLD,
            level = 11,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when
        val result = problem.isHarderThan(Tier.SILVER)

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("문제의 난이도가 주어진 티어의 value와 같거나 낮으면 isHarderThan은 false를 반환한다")
    fun `난이도가 낮거나 같으면 false 반환`() {
        // given: 레벨 8 문제는 SILVER 티어(value=8)보다 어렵지 않다
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.SILVER,
            level = 8,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when
        val sameTierResult = problem.isHarderThan(Tier.SILVER)
        val higherTierResult = problem.isHarderThan(Tier.GOLD)

        // then
        assertThat(sameTierResult).isFalse()
        assertThat(higherTierResult).isFalse()
    }
}


