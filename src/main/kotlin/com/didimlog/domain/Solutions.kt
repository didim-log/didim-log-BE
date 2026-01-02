package com.didimlog.domain

/**
 * Solution 목록을 캡슐화하는 일급 컬렉션
 * 최근 풀이 기록 기반으로 성공률을 계산하는 책임을 가진다.
 */
class Solutions(
    private val items: MutableList<Solution> = mutableListOf()
) {
    /**
     * 새로운 풀이 기록을 컬렉션에 추가한다.
     */
    fun add(solution: Solution) {
        items.add(solution)
    }

    /**
     * 저장된 모든 풀이 기록을 불변 리스트로 반환한다.
     */
    fun getAll(): List<Solution> = items.toList()

    /**
     * 최근 풀이 기록 중 지정한 개수(limit)만큼을 기준으로 성공률을 계산한다.
     * 풀이 기록이 없는 경우 0.0을 반환한다.
     *
     * @param limit 최근 몇 개의 풀이를 기준으로 계산할지 (기본값 10)
     * @return 0.0 ~ 1.0 사이의 성공률 값
     */
    fun calculateRecentSuccessRate(limit: Int = 10): Double {
        if (items.isEmpty()) {
            return 0.0
        }

        val recentItems = items.takeLast(limit)
        val successCount = recentItems.count { it.isSuccess() }

        return successCount.toDouble() / recentItems.size
    }

    /**
     * 특정 문제 ID에 해당하는 풀이 기록을 제거한다.
     * 문제 ID가 일치하는 모든 Solution을 제거한다.
     *
     * @param problemId 제거할 문제 ID
     * @return 제거된 Solution이 있는지 여부
     */
    fun removeByProblemId(problemId: com.didimlog.domain.valueobject.ProblemId): Boolean {
        val initialSize = items.size
        items.removeAll { it.problemId == problemId }
        return items.size < initialSize
    }
}
