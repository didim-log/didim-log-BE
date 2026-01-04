package com.didimlog.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 비밀번호 재설정 코드 엔티티
 * 재설정 코드와 만료 시간을 저장한다.
 */
@Document(collection = "password_reset_codes")
data class PasswordResetCode(
    @Id
    val id: String? = null,
    @Indexed
    val resetCode: String,
    val studentId: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    /**
     * 재설정 코드가 만료되었는지 확인한다.
     *
     * @return 만료 여부
     */
    fun isExpired(): Boolean {
        return LocalDateTime.now().isAfter(expiresAt)
    }
}












