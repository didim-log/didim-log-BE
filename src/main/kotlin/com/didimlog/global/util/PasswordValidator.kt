package com.didimlog.global.util

import com.didimlog.global.exception.InvalidPasswordException

/**
 * 비밀번호 복잡도 검증 유틸리티
 * KISA 권장 기준을 기반으로 비밀번호 정책을 검증한다.
 */
object PasswordValidator {

    private val LETTER_PATTERN = Regex("[a-zA-Z]")
    private val DIGIT_PATTERN = Regex("[0-9]")
    private val SPECIAL_PATTERN = Regex("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]")

    /**
     * 비밀번호 복잡도를 검증한다.
     *
     * 검증 규칙:
     * - 영문(대소문자), 숫자, 특수문자 3가지 종류 중:
     *   - 3종류 이상 조합: 최소 8자리 이상
     *   - 2종류 이상 조합: 최소 10자리 이상
     * - 공백 포함 불가
     *
     * @param password 검증할 비밀번호
     * @throws InvalidPasswordException 비밀번호 정책에 위배되는 경우
     */
    fun validate(password: String) {
        require(password.isNotBlank()) { "비밀번호는 필수입니다." }

        if (password.contains(" ")) {
            throw InvalidPasswordException(
                "비밀번호에 공백을 포함할 수 없습니다."
            )
        }

        val hasLetter = hasLetter(password)
        val hasDigit = hasDigit(password)
        val hasSpecial = hasSpecial(password)

        val typeCount = listOf(hasLetter, hasDigit, hasSpecial).count { it }

        when {
            typeCount >= 3 -> {
                if (password.length < 8) {
                    throw InvalidPasswordException(
                        "영문, 숫자, 특수문자 3종류 이상 조합 시 최소 8자리 이상이어야 합니다."
                    )
                }
            }
            typeCount >= 2 -> {
                if (password.length < 10) {
                    throw InvalidPasswordException(
                        "영문, 숫자, 특수문자 중 2종류 이상 조합 시 최소 10자리 이상이어야 합니다."
                    )
                }
            }
            else -> {
                throw InvalidPasswordException(
                    "영문, 숫자, 특수문자 중 최소 2종류 이상을 조합해야 합니다."
                )
            }
        }
    }

    /**
     * 비밀번호에 영문자가 포함되어 있는지 확인한다.
     */
    private fun hasLetter(password: String): Boolean {
        return LETTER_PATTERN.containsMatchIn(password)
    }

    /**
     * 비밀번호에 숫자가 포함되어 있는지 확인한다.
     */
    private fun hasDigit(password: String): Boolean {
        return DIGIT_PATTERN.containsMatchIn(password)
    }

    /**
     * 비밀번호에 특수문자가 포함되어 있는지 확인한다.
     */
    private fun hasSpecial(password: String): Boolean {
        return SPECIAL_PATTERN.containsMatchIn(password)
    }
}
