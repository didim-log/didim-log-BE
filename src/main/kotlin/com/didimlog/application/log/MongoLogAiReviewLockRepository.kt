package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.valueobject.AiReview
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class MongoLogAiReviewLockRepository(
    private val mongoTemplate: MongoTemplate
) : LogAiReviewLockRepository {

    override fun tryAcquireLock(logId: String, now: LocalDateTime, expiresAt: LocalDateTime): Boolean {
        val query = Query()
        query.addCriteria(Criteria.where(ID_FIELD).`is`(logId))
        query.addCriteria(Criteria.where(AI_REVIEW_FIELD).`is`(null))
        query.addCriteria(lockAvailableCriteria(now))

        val update = Update()
            .set(AI_REVIEW_STATUS_FIELD, AiReviewStatus.IN_PROGRESS)
            .set(AI_REVIEW_LOCK_EXPIRES_AT_FIELD, expiresAt)

        val options = FindAndModifyOptions.options().returnNew(true)
        val updated = mongoTemplate.findAndModify(query, update, options, Log::class.java)
        return updated != null
    }

    override fun isInProgress(logId: String, now: LocalDateTime): Boolean {
        val query = Query()
        query.addCriteria(Criteria.where(ID_FIELD).`is`(logId))
        query.addCriteria(Criteria.where(AI_REVIEW_FIELD).`is`(null))
        query.addCriteria(Criteria.where(AI_REVIEW_STATUS_FIELD).`is`(AiReviewStatus.IN_PROGRESS))
        query.addCriteria(Criteria.where(AI_REVIEW_LOCK_EXPIRES_AT_FIELD).gt(now))
        return mongoTemplate.exists(query, Log::class.java)
    }

    override fun markCompleted(logId: String, review: String, durationMillis: Long): Boolean {
        val query = Query()
        query.addCriteria(Criteria.where(ID_FIELD).`is`(logId))
        query.addCriteria(Criteria.where(AI_REVIEW_FIELD).`is`(null))

        val update = Update()
            .set(AI_REVIEW_FIELD, AiReview(review))
            .set(AI_REVIEW_STATUS_FIELD, AiReviewStatus.COMPLETED)
            .set(AI_REVIEW_DURATION_MILLIS_FIELD, durationMillis)
            .unset(AI_REVIEW_LOCK_EXPIRES_AT_FIELD)

        val result = mongoTemplate.updateFirst(query, update, Log::class.java)
        return result.modifiedCount > 0
    }

    override fun markFailed(logId: String): Boolean {
        val query = Query()
        query.addCriteria(Criteria.where(ID_FIELD).`is`(logId))
        query.addCriteria(Criteria.where(AI_REVIEW_FIELD).`is`(null))

        val update = Update()
            .set(AI_REVIEW_STATUS_FIELD, AiReviewStatus.FAILED)
            .unset(AI_REVIEW_LOCK_EXPIRES_AT_FIELD)

        val result = mongoTemplate.updateFirst(query, update, Log::class.java)
        return result.modifiedCount > 0
    }

    private fun lockAvailableCriteria(now: LocalDateTime): Criteria {
        val lockNotSet = Criteria.where(AI_REVIEW_LOCK_EXPIRES_AT_FIELD).`is`(null)
        val lockExpired = Criteria.where(AI_REVIEW_LOCK_EXPIRES_AT_FIELD).lte(now)
        val statusNotInProgress = Criteria.where(AI_REVIEW_STATUS_FIELD).ne(AiReviewStatus.IN_PROGRESS)

        val lockOk = Criteria().orOperator(lockNotSet, lockExpired)
        return Criteria().orOperator(statusNotInProgress, lockOk)
    }

    companion object {
        private const val ID_FIELD = "_id"
        private const val AI_REVIEW_FIELD = "aiReview"
        private const val AI_REVIEW_STATUS_FIELD = "aiReviewStatus"
        private const val AI_REVIEW_LOCK_EXPIRES_AT_FIELD = "aiReviewLockExpiresAt"
        private const val AI_REVIEW_DURATION_MILLIS_FIELD = "aiReviewDurationMillis"
    }
}


