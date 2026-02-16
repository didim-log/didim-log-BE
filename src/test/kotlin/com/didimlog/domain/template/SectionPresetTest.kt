package com.didimlog.domain.template

import com.didimlog.domain.enums.SectionCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SectionPreset 테스트")
class SectionPresetTest {

    @Test
    fun `프리셋은 성공 실패 공통 카테고리를 모두 포함한다`() {
        val categories = SectionPreset.entries.map { it.category }.toSet()

        assertThat(categories).containsExactlyInAnyOrder(
            SectionCategory.SUCCESS,
            SectionCategory.FAIL,
            SectionCategory.COMMON
        )
    }

    @Test
    fun `각 프리셋은 제목 마크다운 가이드를 가진다`() {
        SectionPreset.entries.forEach { preset ->
            assertThat(preset.title).isNotBlank()
            assertThat(preset.markdownContent).startsWith("## ")
            assertThat(preset.guide).isNotBlank()
        }
    }

    @Test
    fun `COMMENT 프리셋은 contentGuide가 null이다`() {
        assertThat(SectionPreset.COMMENT.contentGuide).isNull()
    }
}

