package com.didimlog.domain.repository

import com.didimlog.domain.Problem
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

/**
 * Problem Repository 구현체
 * MongoDB의 Criteria를 사용하여 동적 쿼리를 구현한다.
 */
@Repository
class ProblemRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : ProblemRepositoryCustom {

    override fun findByLevelBetweenAndTagsIn(min: Int, max: Int, expandedTags: List<String>): List<Problem> {
        // MongoDB에서 배열 필드에 대해 $in 연산자를 사용하여 검색
        // 문제의 tags 리스트 중 하나라도 expandedTags에 포함되면 검색됨
        val criteria = Criteria.where("level")
            .gte(min)
            .lte(max)
            .and("tags")
            .`in`(expandedTags)

        val query = Query(criteria)
        return mongoTemplate.find(query, Problem::class.java)
    }
}

