package com.didimlog.domain.enums

import org.assertj.core.api.Assertions.assertThat
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
    @DisplayName("PLATINUM의 다음 티어는 PLATINUM이다 (최대 티어)")
    fun `PLATINUM 다음 티어는 PLATINUM`() {
        // when
        val nextTier = Tier.PLATINUM.next()

        // then
        assertThat(nextTier).isEqualTo(Tier.PLATINUM)
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
    @DisplayName("PLATINUM은 최대 티어이다")
    fun `PLATINUM은 최대 티어`() {
        // when
        val isNotMax = Tier.PLATINUM.isNotMax()

        // then
        assertThat(isNotMax).isFalse
    }

    @Test
    @DisplayName("각 티어의 level 값이 올바르다")
    fun `티어 level 값 검증`() {
        // then
        assertThat(Tier.BRONZE.level).isEqualTo(1)
        assertThat(Tier.SILVER.level).isEqualTo(2)
        assertThat(Tier.GOLD.level).isEqualTo(3)
        assertThat(Tier.PLATINUM.level).isEqualTo(4)
    }
}
