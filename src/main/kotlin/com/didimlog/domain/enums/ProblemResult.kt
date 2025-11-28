package com.didimlog.domain.enums

/**
 * 문제 풀이 결과를 나타내는 Enum
 * SUCCESS: 문제를 성공적으로 해결함
 * FAIL: 문제를 해결하지 못함
 * TIME_OVER: 제한 시간 내에 해결하지 못함
 */
enum class ProblemResult {
    SUCCESS,
    FAIL,
    TIME_OVER
}
