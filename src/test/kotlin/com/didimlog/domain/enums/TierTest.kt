package com.didimlog.domain.enums

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Tier Enum 테스트")
class TierTest {

    @Test
    @DisplayName("BRONZE의 다음 티어는 SILVER이다")
    fun `BRONZE 다음 티어는 SILVER`() {
        // when
        val nextTier = Tier.BRONZE.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.SILVER)
    }

    @Test
    @DisplayName("SILVER의 다음 티어는 GOLD이다")
    fun `SILVER 다음 티어는 GOLD`() {
        // when
        val nextTier = Tier.SILVER.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.GOLD)
    }

    @Test
    @DisplayName("GOLD의 다음 티어는 PLATINUM이다")
    fun `GOLD 다음 티어는 PLATINUM`() {
        // when
        val nextTier = Tier.GOLD.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.PLATINUM)
    }

    @Test
    @DisplayName("PLATINUM의 다음 티어는 DIAMOND이다")
    fun `PLATINUM 다음 티어는 DIAMOND`() {
        // when
        val nextTier = Tier.PLATINUM.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.DIAMOND)
    }

    @Test
    @DisplayName("DIAMOND의 다음 티어는 RUBY이다")
    fun `DIAMOND 다음 티어는 RUBY`() {
        // when
        val nextTier = Tier.DIAMOND.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.RUBY)
    }

    @Test
    @DisplayName("RUBY의 다음 티어는 RUBY이다 (최대 티어)")
    fun `RUBY 다음 티어는 RUBY`() {
        // when
        val nextTier = Tier.RUBY.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.RUBY)
    }

    @Test
    @DisplayName("BRONZE는 최대 티어가 아니다")
    fun `BRONZE는 최대 티어 아님`() {
        // when
        val isNotMax = Tier.BRONZE.isNotMax()

        // then
        assertThat(isNotMax).isTrue
    }

    @Test
    @DisplayName("SILVER는 최대 티어가 아니다")
    fun `SILVER는 최대 티어 아님`() {
        // when
        val isNotMax = Tier.SILVER.isNotMax()

        // then
        assertThat(isNotMax).isTrue
    }

    @Test
    @DisplayName("GOLD는 최대 티어가 아니다")
    fun `GOLD는 최대 티어 아님`() {
        // when
        val isNotMax = Tier.GOLD.isNotMax()

        // then
        assertThat(isNotMax).isTrue
    }

    @Test
    @DisplayName("PLATINUM은 최대 티어가 아니다")
    fun `PLATINUM은 최대 티어 아님`() {
        // when
        val isNotMax = Tier.PLATINUM.isNotMax()

        // then
        assertThat(isNotMax).isTrue
    }

    @Test
    @DisplayName("DIAMOND는 최대 티어가 아니다")
    fun `DIAMOND는 최대 티어 아님`() {
        // when
        val isNotMax = Tier.DIAMOND.isNotMax()

        // then
        assertThat(isNotMax).isTrue
    }

    @Test
    @DisplayName("RUBY는 최대 티어이다")
    fun `RUBY는 최대 티어`() {
        // when
        val isNotMax = Tier.RUBY.isNotMax()

        // then
        assertThat(isNotMax).isFalse
    }

    @Test
    @DisplayName("각 티어의 value 값이 올바르다 (Solved.ac 레벨 대표값)")
    fun `티어 value 값 검증`() {
        // then
        assertThat(Tier.BRONZE.value).isEqualTo(3)
        assertThat(Tier.SILVER.value).isEqualTo(8)
        assertThat(Tier.GOLD.value).isEqualTo(13)
        assertThat(Tier.PLATINUM.value).isEqualTo(18)
        assertThat(Tier.DIAMOND.value).isEqualTo(23)
        assertThat(Tier.RUBY.value).isEqualTo(28)
    }

    @Test
    @DisplayName("각 티어의 minLevel과 maxLevel이 올바르다")
    fun `티어 레벨 범위 검증`() {
        // then
        assertThat(Tier.BRONZE.minLevel).isEqualTo(1)
        assertThat(Tier.BRONZE.maxLevel).isEqualTo(5)
        assertThat(Tier.SILVER.minLevel).isEqualTo(6)
        assertThat(Tier.SILVER.maxLevel).isEqualTo(10)
        assertThat(Tier.GOLD.minLevel).isEqualTo(11)
        assertThat(Tier.GOLD.maxLevel).isEqualTo(15)
        assertThat(Tier.PLATINUM.minLevel).isEqualTo(16)
        assertThat(Tier.PLATINUM.maxLevel).isEqualTo(20)
        assertThat(Tier.DIAMOND.minLevel).isEqualTo(21)
        assertThat(Tier.DIAMOND.maxLevel).isEqualTo(25)
        assertThat(Tier.RUBY.minLevel).isEqualTo(26)
        assertThat(Tier.RUBY.maxLevel).isEqualTo(30)
    }

    @Test
    @DisplayName("Tier.from은 Solved.ac 레벨을 올바른 Tier로 변환한다")
    fun `Tier_from으로 레벨 변환`() {
        // then
        assertThat(Tier.from(1)).isEqualTo(Tier.BRONZE)
        assertThat(Tier.from(3)).isEqualTo(Tier.BRONZE)
        assertThat(Tier.from(5)).isEqualTo(Tier.BRONZE)
        assertThat(Tier.from(6)).isEqualTo(Tier.SILVER)
        assertThat(Tier.from(8)).isEqualTo(Tier.SILVER)
        assertThat(Tier.from(10)).isEqualTo(Tier.SILVER)
        assertThat(Tier.from(11)).isEqualTo(Tier.GOLD)
        assertThat(Tier.from(13)).isEqualTo(Tier.GOLD)
        assertThat(Tier.from(15)).isEqualTo(Tier.GOLD)
        assertThat(Tier.from(16)).isEqualTo(Tier.PLATINUM)
        assertThat(Tier.from(18)).isEqualTo(Tier.PLATINUM)
        assertThat(Tier.from(20)).isEqualTo(Tier.PLATINUM)
        assertThat(Tier.from(21)).isEqualTo(Tier.DIAMOND)
        assertThat(Tier.from(23)).isEqualTo(Tier.DIAMOND)
        assertThat(Tier.from(25)).isEqualTo(Tier.DIAMOND)
        assertThat(Tier.from(26)).isEqualTo(Tier.RUBY)
        assertThat(Tier.from(28)).isEqualTo(Tier.RUBY)
        assertThat(Tier.from(30)).isEqualTo(Tier.RUBY)
    }

    @Test
    @DisplayName("Tier.from은 0 이하 레벨에 대해 예외를 발생시킨다")
    fun `Tier_from은 0 이하 레벨 예외`() {
        // expect
        assertThatThrownBy { Tier.from(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("레벨은 1 이상이어야 합니다")
    }

    @Test
    @DisplayName("Tier.from은 30 초과 레벨에 대해 예외를 발생시킨다")
    fun `Tier_from은 30 초과 레벨 예외`() {
        // expect
        assertThatThrownBy { Tier.from(31) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("레벨은 30 이하여야 합니다")
    }
}
