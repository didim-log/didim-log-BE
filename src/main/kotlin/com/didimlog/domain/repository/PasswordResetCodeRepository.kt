package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode
import java.util.Optional
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * 비밀번호 재설정 코드 Repository
 */
interface PasswordResetCodeRepository :
    MongoRepository<PasswordResetCode, String>,
    PasswordResetCodeRepositoryCustom {

    fun findByResetCode(resetCode: String): Optional<PasswordResetCode>
}













