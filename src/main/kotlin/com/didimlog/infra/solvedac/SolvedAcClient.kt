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

    /**
     * Solved.ac 문제 레벨을 Tier로 변환한다.
     * 레벨이 0 이하이거나 30 초과인 경우 예외를 발생시킨다.
     *
     * @param level Solved.ac 문제 레벨 (1~30)
     * @return 해당 레벨에 맞는 Tier
     */
    fun fromProblemLevel(level: Int): Tier {
        return Tier.from(level)
    }

    /**
     * Solved.ac 사용자 티어 레벨을 Tier로 변환한다.
     * 레벨이 0 이하이거나 30 초과인 경우 예외를 발생시킨다.
     *
     * @param tier Solved.ac 사용자 티어 레벨 (1~30)
     * @return 해당 레벨에 맞는 Tier
     */
    fun fromUserTier(tier: Int): Tier {
        return Tier.from(tier)
    }
}


