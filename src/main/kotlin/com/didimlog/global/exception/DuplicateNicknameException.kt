package com.didimlog.global.exception

/**
 * 닉네임 중복 예외
 * - 전역 예외 처리에서 ErrorCode.DUPLICATE_NICKNAME으로 변환한다.
 */
class DuplicateNicknameException(
    message: String = ErrorCode.DUPLICATE_NICKNAME.message
) : IllegalStateException(message)




