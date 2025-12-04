package com.didimlog.domain.repository

import com.didimlog.domain.Retrospective
import org.springframework.data.mongodb.repository.MongoRepository

interface RetrospectiveRepository : MongoRepository<Retrospective, String>, RetrospectiveRepositoryCustom {

    fun findAllByStudentId(studentId: String): List<Retrospective>

    fun findByStudentIdAndProblemId(studentId: String, problemId: String): Retrospective?
}


