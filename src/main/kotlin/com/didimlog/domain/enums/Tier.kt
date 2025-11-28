package com.didimlog.domain.enums

/**
 * 사용자의 알고리즘 실력을 나타내는 티어 등급
 * BRONZE(1) -> SILVER(2) -> GOLD(3) -> PLATINUM(4) 순서로 성장한다.
 *
 * @property level 티어의 숫자 레벨 (낮을수록 초급)
 */
enum class Tier(val level: Int) {
    BRONZE(1),
    SILVER(2),
    GOLD(3),
    PLATINUM(4);

    /**
     * 다음 단계 티어를 반환한다.
     * 최대 티어인 경우 현재 티어를 반환한다.
     *
     * @return 다음 단계 티어 또는 현재 티어 (최대 티어인 경우)
     */
    fun next(): Tier = entries.find { it.level == this.level + 1 } ?: this

    /**
     * 현재 티어가 최대 티어가 아닌지 확인한다.
     *
     * @return 최대 티어가 아니면 true, 최대 티어면 false
     */
    fun isNotMax(): Boolean = this != PLATINUM
}
