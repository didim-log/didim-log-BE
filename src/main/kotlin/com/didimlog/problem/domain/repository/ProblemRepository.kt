package com.didimlog.problem.domain.repository

import com.didimlog.problem.domain.Problem
import com.didimlog.problem.domain.ProblemId
import org.springframework.data.mongodb.repository.MongoRepository

interface ProblemRepository : MongoRepository<Problem, String>, ProblemRepositoryCustom {

    fun findByLevelBetween(min: Int, max: Int): List<Problem>

    fun findByDescriptionHtmlIsNull(): List<Problem>

    /**
     * 언어 정보가 null인 문제들을 조회한다.
     */
    fun findByLanguageIsNull(): List<Problem>

    /**
     * 언어 정보가 "other"인 문제들을 조회한다.
     */
    fun findByLanguage(other: String): List<Problem>
}
