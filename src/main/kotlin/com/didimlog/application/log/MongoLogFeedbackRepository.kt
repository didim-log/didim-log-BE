package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class MongoLogFeedbackRepository(
    private val mongoTemplate: MongoTemplate
) : LogFeedbackRepository {

    override fun updateFeedback(
        logId: String,
        studentId: String,
        status: AiFeedbackStatus,
        reason: String?
    ): Log? {
        val query = Query.query(
            Criteria.where(ID_FIELD).`is`(logId)
                .and(STUDENT_ID_FIELD).`is`(studentId)
        )
        val update = Update().set(AI_FEEDBACK_STATUS_FIELD, status)
        if (reason == null) {
            update.unset(AI_FEEDBACK_REASON_FIELD)
        } else {
            update.set(AI_FEEDBACK_REASON_FIELD, reason)
        }

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(false),
            Log::class.java
        )
    }

    companion object {
        private const val ID_FIELD = "_id"
        private const val STUDENT_ID_FIELD = "studentId"
        private const val AI_FEEDBACK_STATUS_FIELD = "aiFeedbackStatus"
        private const val AI_FEEDBACK_REASON_FIELD = "aiFeedbackReason"
    }
}
