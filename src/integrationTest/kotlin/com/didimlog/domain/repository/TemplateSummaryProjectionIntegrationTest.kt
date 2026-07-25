package com.didimlog.domain.repository

import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.template.Template
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest

@DisplayName("Template 요약 projection 통합 테스트")
@DataMongoTest
class TemplateSummaryProjectionIntegrationTest {

    @Autowired
    private lateinit var templateRepository: TemplateRepository

    @BeforeEach
    fun setUp() {
        templateRepository.deleteAll()
    }

    @Test
    @DisplayName("content를 제외한 MongoDB 문서를 요약 DTO로 매핑한다")
    fun mapsSummaryProjectionWithoutContent() {
        val createdAt = LocalDateTime.now().withNano(0)
        val systemTemplate = templateRepository.save(
            Template(
                title = "시스템 템플릿",
                content = "목록 조회에서 제외할 본문",
                type = TemplateOwnershipType.SYSTEM,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
        val customTemplate = templateRepository.save(
            Template(
                studentId = "student-1",
                title = "사용자 템플릿",
                content = "목록 조회에서 제외할 사용자 본문",
                type = TemplateOwnershipType.CUSTOM,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
        templateRepository.save(
            Template(
                studentId = "student-2",
                title = "다른 사용자 템플릿",
                content = "조회 대상이 아닌 본문",
                type = TemplateOwnershipType.CUSTOM
            )
        )

        val summaries = templateRepository.findSummaryByStudentIdOrType(
            "student-1",
            TemplateOwnershipType.SYSTEM
        )

        assertThat(summaries).hasSize(2)
        assertThat(summaries.map { it.title })
            .containsExactlyInAnyOrder("시스템 템플릿", "사용자 템플릿")

        val systemSummary = summaries.single { it.title == "시스템 템플릿" }
        assertThat(systemSummary.id).isEqualTo(systemTemplate.id)
        assertThat(systemSummary.studentId).isNull()
        assertThat(systemSummary.type).isEqualTo(TemplateOwnershipType.SYSTEM)
        assertThat(systemSummary.createdAt).isEqualTo(createdAt)

        val customSummary = summaries.single { it.title == "사용자 템플릿" }
        assertThat(customSummary.id).isEqualTo(customTemplate.id)
        assertThat(customSummary.studentId).isEqualTo("student-1")
        assertThat(customSummary.type).isEqualTo(TemplateOwnershipType.CUSTOM)
        assertThat(customSummary.updatedAt).isEqualTo(createdAt)
    }
}
