package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode

interface PasswordResetCodeRepositoryCustom {

    /**
     * 재설정 코드를 조회와 동시에 삭제한다.
     *
     * @param resetCode 소비할 재설정 코드
     * @return 소비한 코드, 코드가 없으면 null
     */
    fun consumeByResetCode(resetCode: String): PasswordResetCode?
}
