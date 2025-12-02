package com.didimlog.infra.solvedac

import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

interface SolvedAcClient {

    fun fetchProblem(problemId: Int): SolvedAcProblemResponse

    fun fetchUser(bojId: BojId): SolvedAcUserResponse
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolvedAcProblemResponse(
    val problemId: Int,
    val titleKo: String,
    val level: Int,
    val tags: List<SolvedAcTag> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolvedAcTag(
    val key: String,
    @JsonProperty("isMeta")
    val isMeta: Boolean = false,
    val displayNames: List<SolvedAcTagDisplayName> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolvedAcTagDisplayName(
    val language: String,
    val name: String,
    val short: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolvedAcUserResponse(
    val handle: String,
    @JsonProperty("rating")
    val rating: Int  // Solved.ac API의 rating 필드 (0이면 Unrated)
) {
    /**
     * rating 값을 tier로 변환 (0이면 1로 처리하여 BRONZE로 매핑)
     */
    val tier: Int
        get() = if (rating <= 0) 1 else rating
}

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


