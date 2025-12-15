package com.didimlog.global.exception

/**
 * 비밀번호 정책 위반 예외
 * 비밀번호 복잡도 검증에 실패한 경우 발생한다.
 */
class InvalidPasswordException(
    message: String
) : RuntimeException(message)

