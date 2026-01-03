package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode
import org.springframework.data.mongodb.repository.MongoRepository
import java.util.Optional

/**
 * 비밀번호 재설정 코드 Repository
 */
interface PasswordResetCodeRepository : MongoRepository<PasswordResetCode, String> {

    /**
     * 재설정 코드로 비밀번호 재설정 코드를 조회한다.
     *
     * @param resetCode 재설정 코드
     * @return 비밀번호 재설정 코드 (없으면 Optional.empty())
     */
    fun findByResetCode(resetCode: String): Optional<PasswordResetCode>

    /**
     * 재설정 코드로 비밀번호 재설정 코드를 삭제한다.
     *
     * @param resetCode 재설정 코드
     */
    fun deleteByResetCode(resetCode: String)
}





