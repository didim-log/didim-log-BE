package com.didimlog.domain.enums

/**
 * 사용자의 알고리즘 실력을 나타내는 티어 등급
 * Solved.ac의 Rating(점수) 시스템을 Source of Truth로 사용한다.
 * BRONZE(30~150) -> SILVER(200~650) -> GOLD(800~1400) -> PLATINUM(1600~) -> DIAMOND -> RUBY 순서로 성장한다.
 *
 * @property value Solved.ac 레벨 범위의 대표값 (티어 그룹의 중간값 또는 최소값)
 * @property minLevel Solved.ac 레벨 범위의 최소값
 * @property maxLevel Solved.ac 레벨 범위의 최대값
 * @property minRating 해당 티어 달성 최소 점수 (Solved.ac Rating 기준)
 */
enum class Tier(val value: Int, val minLevel: Int, val maxLevel: Int, val minRating: Int) {
    UNRATED(0, 0, 0, 0),      // Unrated (0점)
    BRONZE(3, 1, 5, 30),      // Bronze V~I (30~150점)
    SILVER(8, 6, 10, 200),    // Silver V~I (200~650점)
    GOLD(13, 11, 15, 800),    // Gold V~I (800~1400점)
    PLATINUM(18, 16, 20, 1600), // Platinum V~I (1600점 이상)
    DIAMOND(23, 21, 25, 2200), // Diamond V~I (2200점 이상)
    RUBY(28, 26, 30, 2700);    // Ruby V~I (2700점 이상)

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

        /**
         * Solved.ac Rating(점수)을 받아서 해당하는 Tier를 반환한다.
         * 점수가 높을수록 더 높은 티어를 반환한다.
         *
         * @param rating Solved.ac Rating (점수)
         * @return 해당 점수에 맞는 Tier
         */
        fun fromRating(rating: Int): Tier {
            if (rating < 0) {
                return UNRATED
            }

            // 점수가 높은 티어부터 확인하여 해당하는 티어 반환
            return entries
                .filter { it != UNRATED }
                .sortedByDescending { it.minRating }
                .find { rating >= it.minRating }
                ?: UNRATED
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
