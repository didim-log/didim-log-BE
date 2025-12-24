package com.didimlog.domain.enums

import kotlin.math.roundToInt

/**
 * Solved.ac 티어(세부 단계) 기준표
 * - Bronze V ~ Master
 * - 기준: solved.ac Help 문서의 AC Rating Threshold
 *
 * ※ 정책: Master를 최고 단계로 취급한다.
 */
enum class SolvedAcTierStep(
    val order: Int,
    val title: String,
    val minRating: Int
) {
    UNRATED(0, "Unrated", 0),

    BRONZE_V(1, "Bronze V", 30),
    BRONZE_IV(2, "Bronze IV", 60),
    BRONZE_III(3, "Bronze III", 90),
    BRONZE_II(4, "Bronze II", 120),
    BRONZE_I(5, "Bronze I", 150),

    SILVER_V(6, "Silver V", 200),
    SILVER_IV(7, "Silver IV", 300),
    SILVER_III(8, "Silver III", 400),
    SILVER_II(9, "Silver II", 500),
    SILVER_I(10, "Silver I", 650),

    GOLD_V(11, "Gold V", 800),
    GOLD_IV(12, "Gold IV", 950),
    GOLD_III(13, "Gold III", 1100),
    GOLD_II(14, "Gold II", 1250),
    GOLD_I(15, "Gold I", 1400),

    PLATINUM_V(16, "Platinum V", 1600),
    PLATINUM_IV(17, "Platinum IV", 1750),
    PLATINUM_III(18, "Platinum III", 1900),
    PLATINUM_II(19, "Platinum II", 2000),
    PLATINUM_I(20, "Platinum I", 2100),

    DIAMOND_V(21, "Diamond V", 2200),
    DIAMOND_IV(22, "Diamond IV", 2300),
    DIAMOND_III(23, "Diamond III", 2400),
    DIAMOND_II(24, "Diamond II", 2500),
    DIAMOND_I(25, "Diamond I", 2600),

    RUBY_V(26, "Ruby V", 2700),
    RUBY_IV(27, "Ruby IV", 2800),
    RUBY_III(28, "Ruby III", 2850),
    RUBY_II(29, "Ruby II", 2900),
    RUBY_I(30, "Ruby I", 2950),

    MASTER(31, "Master", 3000);

    companion object {
        fun fromRating(rating: Int): SolvedAcTierStep {
            if (rating < 0) {
                return UNRATED
            }

            return entries
                .sortedByDescending { it.minRating }
                .first { rating >= it.minRating }
        }
    }

    fun nextOrNull(): SolvedAcTierStep? {
        return entries
            .sortedBy { it.order }
            .firstOrNull { it.order == this.order + 1 }
    }

    fun calculateProgress(rating: Int): TierProgress {
        val current = fromRating(rating)
        val next = current.nextOrNull()

        if (next == null) {
            return TierProgress(
                currentTierTitle = current.title,
                nextTierTitle = current.title,
                currentRating = rating,
                requiredRatingForNextTier = current.minRating,
                progressPercentage = 100
            )
        }

        val currentMin = current.minRating
        val nextMin = next.minRating
        val clampedRating = rating.coerceAtLeast(currentMin).coerceAtMost(nextMin)

        val ratio = if (nextMin == currentMin) {
            1.0
        } else {
            (clampedRating - currentMin).toDouble() / (nextMin - currentMin).toDouble()
        }

        return TierProgress(
            currentTierTitle = current.title,
            nextTierTitle = next.title,
            currentRating = rating,
            requiredRatingForNextTier = nextMin,
            progressPercentage = (ratio * 100.0).roundToInt().coerceIn(0, 100)
        )
    }
}

data class TierProgress(
    val currentTierTitle: String,
    val nextTierTitle: String,
    val currentRating: Int,
    val requiredRatingForNextTier: Int,
    val progressPercentage: Int
)
