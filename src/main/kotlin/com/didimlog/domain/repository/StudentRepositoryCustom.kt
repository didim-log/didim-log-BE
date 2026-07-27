package com.didimlog.domain.repository

import com.didimlog.domain.Student
import java.time.LocalDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface StudentRepositoryCustom {

    fun searchAdminUsers(
        pageable: Pageable,
        search: String?,
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Page<Student>
}
