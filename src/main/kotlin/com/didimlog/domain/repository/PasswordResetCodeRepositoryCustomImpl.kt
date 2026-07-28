package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class PasswordResetCodeRepositoryCustomImpl(
    private val mongoTemplate: MongoTemplate
) : PasswordResetCodeRepositoryCustom {

    override fun consumeByResetCode(resetCode: String): PasswordResetCode? {
        val query = Query.query(Criteria.where("resetCode").`is`(resetCode))
        return mongoTemplate.findAndRemove(query, PasswordResetCode::class.java)
    }
}
