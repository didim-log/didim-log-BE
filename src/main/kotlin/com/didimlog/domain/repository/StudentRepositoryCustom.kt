package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
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
