package com.didimlog.domain.validation

/**
 * 닉네임 정책
 *
 * - 허용: 영문(대소문자), 숫자, 완성형 한글(가-힣), 특수문자(., _, -)
 * - 금지: 공백, 그 외 특수문자, 한글 자모(ㄱ-ㅎ, ㅏ-ㅣ), 예약어(admin/manager 등)
 * - 길이: 2~12
 */
object NicknamePolicy {
    private val allowedPattern = Regex("^[a-zA-Z0-9가-힣._-]{2,12}$")
    private val reservedWords = setOf("admin", "manager")

    fun validate(value: String) {
        require(value.isNotBlank()) { "닉네임은 필수입니다." }
        require(isLengthValid(value)) { "닉네임은 2자 이상 12자 이하여야 합니다." }
        require(isPatternValid(value)) { "닉네임 형식이 올바르지 않습니다." }
        require(!isReserved(value)) { "사용할 수 없는 닉네임입니다." }
    }

    fun isReserved(value: String): Boolean {
        val normalized = value.lowercase()
        return reservedWords.contains(normalized)
    }

    private fun isLengthValid(value: String): Boolean = value.length in 2..12

    private fun isPatternValid(value: String): Boolean = allowedPattern.matches(value)
}


