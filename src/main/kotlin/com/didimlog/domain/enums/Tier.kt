package com.didimlog.domain.enums

/**
 * 사용자의 알고리즘 실력을 나타내는 티어 등급
 * Solved.ac의 1~30 레벨 시스템을 Source of Truth로 사용한다.
 * BRONZE(1~5) -> SILVER(6~10) -> GOLD(11~15) -> PLATINUM(16~20) -> DIAMOND(21~25) -> RUBY(26~30) 순서로 성장한다.
 *
 * @property value Solved.ac 레벨 범위의 대표값 (티어 그룹의 중간값 또는 최소값)
 * @property minLevel Solved.ac 레벨 범위의 최소값
 * @property maxLevel Solved.ac 레벨 범위의 최대값
 */
enum class Tier(val value: Int, val minLevel: Int, val maxLevel: Int) {
    BRONZE(3, 1, 5),      // Bronze V~I (1~5), 대표값: 3
    SILVER(8, 6, 10),    // Silver V~I (6~10), 대표값: 8
    GOLD(13, 11, 15),    // Gold V~I (11~15), 대표값: 13
    PLATINUM(18, 16, 20), // Platinum V~I (16~20), 대표값: 18
    DIAMOND(23, 21, 25), // Diamond V~I (21~25), 대표값: 23
    RUBY(28, 26, 30);    // Ruby V~I (26~30), 대표값: 28

    companion object {
        /**
         * Solved.ac 레벨(1~30)을 받아서 해당하는 Tier를 반환한다.
         * 0은 Unrated로 처리하여 예외를 발생시킨다.
         *
         * @param level Solved.ac 레벨 (1~30)
         * @return 해당 레벨에 맞는 Tier
         * @throws IllegalArgumentException level이 0 이하이거나 30 초과인 경우
         */
        fun from(level: Int): Tier {
            if (level <= 0) {
                throw IllegalArgumentException("레벨은 1 이상이어야 합니다. level=$level (0은 Unrated입니다)")
            }
            if (level > 30) {
                throw IllegalArgumentException("레벨은 30 이하여야 합니다. level=$level")
            }

            return entries.find { level in it.minLevel..it.maxLevel }
                ?: throw IllegalStateException("레벨에 해당하는 티어를 찾을 수 없습니다. level=$level")
        }
    }

    /**
     * 다음 단계 티어를 반환한다.
     * 최대 티어인 경우 현재 티어를 반환한다.
     *
     * @return 다음 단계 티어 또는 현재 티어 (최대 티어인 경우)
     */
    fun next(): Tier = entries.find { it.value > this.value } ?: this

    /**
     * 현재 티어가 최대 티어가 아닌지 확인한다.
     *
     * @return 최대 티어가 아니면 true, 최대 티어면 false
     */
    fun isNotMax(): Boolean = this != RUBY
}
