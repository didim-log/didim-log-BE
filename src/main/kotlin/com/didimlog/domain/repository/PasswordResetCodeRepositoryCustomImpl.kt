package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode
import java.time.LocalDateTime
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class PasswordResetCodeRepositoryCustomImpl(
    private val mongoTemplate: MongoTemplate
) : PasswordResetCodeRepositoryCustom {

    override fun issueForStudent(
        studentId: String,
        resetCode: String,
        expiresAt: LocalDateTime,
        createdAt: LocalDateTime,
        credentialVersion: Long,
        bojId: String
    ): PasswordResetCode {
        val query = Query.query(Criteria.where("studentId").`is`(studentId))
        val update = Update()
            .set("resetCode", resetCode)
            .set("credentialVersion", credentialVersion)
            .set("bojId", bojId)
            .set("expiresAt", expiresAt)
            .set("createdAt", createdAt)
        val options = FindAndModifyOptions.options()
            .upsert(true)
            .returnNew(true)

        return checkNotNull(
            mongoTemplate.findAndModify(
                query,
                update,
                options,
                PasswordResetCode::class.java
            )
        ) {
            "비밀번호 재설정 코드 upsert 결과를 찾을 수 없습니다. studentId=$studentId"
        }
    }

    override fun consumeByResetCode(resetCode: String, expectedStudentId: String): PasswordResetCode? {
        val query = Query.query(
            Criteria.where("resetCode").`is`(resetCode)
                .and("studentId").`is`(expectedStudentId)
        )
        return mongoTemplate.findAndRemove(query, PasswordResetCode::class.java)
    }

    override fun deleteIssuedCode(studentId: String, resetCode: String): Boolean {
        val query = Query.query(
            Criteria.where("studentId").`is`(studentId)
                .and("resetCode").`is`(resetCode)
        )
        return mongoTemplate.remove(query, PasswordResetCode::class.java).deletedCount == 1L
    }
}
