package com.didimlog.application.auth.boj

/**
 * BOJ 프로필 상태 메시지 조회 클라이언트
 */
interface BojProfileStatusMessageClient {

    fun fetchStatusMessage(bojId: String): BojProfileStatusMessageFetchResult
}

