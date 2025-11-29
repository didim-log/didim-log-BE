package com.didimlog.domain.repository

import com.didimlog.domain.Problem
import com.didimlog.domain.valueobject.ProblemId
import org.springframework.data.mongodb.repository.MongoRepository

interface ProblemRepository : MongoRepository<Problem, ProblemId> {

    fun findByDifficultyLevelBetween(min: Int, max: Int): List<Problem>
}


