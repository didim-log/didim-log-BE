package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import java.util.Optional
import org.springframework.data.mongodb.repository.MongoRepository

interface StudentRepository : MongoRepository<Student, String> {

    fun findByBojId(bojId: BojId): Optional<Student>

    fun existsByBojId(bojId: BojId): Boolean

    fun existsByNickname(nickname: Nickname): Boolean
}



