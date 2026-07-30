package com.didimlog.application.auth.oauth

import com.didimlog.domain.enums.Role

/**
 * OAuth 로그인 교환 코드를 짧은 시간 동안 보관하고 한 번만 소비한다.
 */
interface OAuthExchangeCodeStore {
    fun save(code: String, identity: OAuthExchangeCodeIdentity, ttlSeconds: Long): Boolean

    fun consume(code: String): OAuthExchangeCodeIdentity?
}

data class OAuthExchangeCodeIdentity(
    val studentId: String,
    val bojId: String,
    val credentialVersion: Long,
    val role: Role
) {
    init {
        require(studentId.isNotBlank()) { "studentId는 비어 있을 수 없습니다." }
        require(bojId.isNotBlank()) { "bojId는 비어 있을 수 없습니다." }
        require(credentialVersion >= 0) { "credentialVersion은 음수일 수 없습니다." }
        require(role == Role.USER || role == Role.ADMIN) {
            "OAuth 교환 코드는 USER 또는 ADMIN 권한에만 발급할 수 있습니다."
        }
    }
}
