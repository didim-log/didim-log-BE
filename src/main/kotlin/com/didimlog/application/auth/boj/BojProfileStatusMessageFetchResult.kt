package com.didimlog.application.auth.boj

/**
 * BOJ 프로필 상태 메시지 조회 결과
 * - 외부 사이트(BOJ) 크롤링 결과를 비즈니스 로직에서 해석 가능하도록 상태로 분리한다.
 */
sealed interface BojProfileStatusMessageFetchResult {

    data class Found(val statusMessage: BojProfileStatusMessage) : BojProfileStatusMessageFetchResult

    data object UserNotFound : BojProfileStatusMessageFetchResult

    data object AccessDenied : BojProfileStatusMessageFetchResult

    /**
     * 프로필 페이지는 가져왔지만, 상태 메시지 영역을 찾지 못한 경우
     * (HTML 구조 변경, 상태 메시지 미설정 등)
     */
    data object StatusMessageNotFound : BojProfileStatusMessageFetchResult

    data class Failed(val reason: String) : BojProfileStatusMessageFetchResult
}

@JvmInline
value class BojProfileStatusMessage(val value: String) {
    init {
        require(value.isNotBlank()) { "상태 메시지는 비어 있을 수 없습니다." }
    }
}




