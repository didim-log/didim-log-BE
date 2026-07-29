package com.didimlog.application.auth

/**
 * 비밀번호 재설정에 사용할 일회성 코드를 생성한다.
 */
interface PasswordResetCodeGenerator {

    fun generate(): String
}
