package com.didimlog.domain.repository

import com.didimlog.domain.Student
import java.time.LocalDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface StudentRepositoryCustom {

    fun updatePasswordById(studentId: String, encodedPassword: String): Boolean

    fun searchAdminUsers(
        pageable: Pageable,
        search: String?,
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Page<Student>
}
