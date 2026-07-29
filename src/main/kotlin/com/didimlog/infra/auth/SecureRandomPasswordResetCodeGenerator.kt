package com.didimlog.infra.auth

import com.didimlog.application.auth.PasswordResetCodeGenerator
import java.security.SecureRandom
import org.springframework.stereotype.Component

@Component
class SecureRandomPasswordResetCodeGenerator : PasswordResetCodeGenerator {

    private val secureRandom = SecureRandom()

    override fun generate(): String {
        return buildString(RESET_CODE_LENGTH) {
            repeat(RESET_CODE_LENGTH) {
                append(RESET_CODE_CHARS[secureRandom.nextInt(RESET_CODE_CHARS.length)])
            }
        }
    }

    private companion object {
        const val RESET_CODE_LENGTH = 8
        const val RESET_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
