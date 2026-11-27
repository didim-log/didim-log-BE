package com.didimlog.student.domain.repository

import com.didimlog.student.domain.Student
import com.didimlog.study.domain.Solutions
import com.didimlog.student.domain.PrimaryLanguage
import com.didimlog.template.domain.TemplateCategory
import com.didimlog.student.domain.Tier
import com.didimlog.student.domain.BojId
import com.didimlog.student.domain.Nickname
import com.didimlog.student.domain.SolvedAcTierLevel
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface StudentRepositoryCustom {

    fun updatePasswordById(
        studentId: String,
        encodedPassword: String,
        expectedCredentialVersion: Long,
        expectedBojId: BojId
    ): Boolean

    fun updateProfileFieldsById(
        studentId: String,
        nickname: Nickname?,
        encodedPassword: String?,
        primaryLanguage: PrimaryLanguage?,
        expectedCredentialVersion: Long
    ): Student?

    fun updateSolvedAcProfileById(
        studentId: String,
        expectedBojId: BojId,
        rating: Int,
        solvedAcTierLevel: SolvedAcTierLevel,
        currentTier: Tier
    ): Student?

    fun updateStudyProgressById(
        studentId: String,
        expectedDocumentVersion: Long,
        solutions: Solutions,
        consecutiveSolveDays: Int,
        lastSolvedAt: LocalDate?
    ): Student?

    fun updateDefaultTemplateById(
        studentId: String,
        category: TemplateCategory,
        templateId: String
    ): Student?

    fun clearDefaultTemplateReferences(
        studentId: String,
        expectedTemplateId: String,
        categories: Set<TemplateCategory>
    ): Student?

    fun searchAdminUsers(
        pageable: Pageable,
        search: String?,
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Page<Student>
}
