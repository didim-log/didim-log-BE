package com.didimlog.domain.valueobject

import com.didimlog.domain.enums.SolvedAcTierStep

/**
 * Solved.ac의 사용자 티어 레벨을 나타내는 Value Object
 *
 * - 0: Unrated
 * - 1: Bronze 5
 * - 2: Bronze 4
 * - ...
 * - 30: Ruby 1
 * - 31: Master
 */
@JvmInline
value class SolvedAcTierLevel(val value: Int) {
    init {
        require(value in 0..31) { "Solved.ac tier 레벨은 0~31 사이여야 합니다. tier=$value" }
    }

    fun isUnrated(): Boolean = value == 0

    companion object {
        fun fromRating(rating: Int): SolvedAcTierLevel {
            return SolvedAcTierLevel(SolvedAcTierStep.fromRating(rating).order)
        }
    }
}


