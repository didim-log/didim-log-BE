package com.didimlog.infra.solvedac

import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId

interface SolvedAcClient {

    fun fetchProblem(problemId: Int): SolvedAcProblemResponse

    fun fetchUser(bojId: BojId): SolvedAcUserResponse
}

data class SolvedAcProblemResponse(
    val problemId: Int,
    val titleKo: String,
    val level: Int
)

data class SolvedAcUserResponse(
    val handle: String,
    val tier: Int
)

object SolvedAcTierMapper {

    fun fromProblemLevel(level: Int): Tier {
        if (level <= 0) {
            return Tier.BRONZE
        }
        if (level <= 5) {
            return Tier.BRONZE
        }
        if (level <= 10) {
            return Tier.SILVER
        }
        if (level <= 20) {
            return Tier.GOLD
        }
        return Tier.PLATINUM
    }

    fun fromUserTier(tier: Int): Tier {
        if (tier <= 0) {
            return Tier.BRONZE
        }
        if (tier <= 5) {
            return Tier.BRONZE
        }
        if (tier <= 10) {
            return Tier.SILVER
        }
        if (tier <= 20) {
            return Tier.GOLD
        }
        return Tier.PLATINUM
    }
}


