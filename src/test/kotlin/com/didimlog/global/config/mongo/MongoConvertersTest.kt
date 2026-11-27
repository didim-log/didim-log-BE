package com.didimlog.global.config.mongo

import com.didimlog.problem.domain.ProblemCategory
import com.didimlog.student.domain.Tier
import com.didimlog.log.domain.AiReview
import com.didimlog.student.domain.BojId
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.student.domain.Nickname
import com.didimlog.student.domain.SolvedAcTierLevel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("MongoConverters 테스트")
class MongoConvertersTest {

    @Test
    @DisplayName("문자열 Value Object는 원래 값으로 저장되고 같은 값으로 복원된다")
    fun `문자열 Value Object 왕복 변환`() {
        assertThat(NicknameReadConverter().convert(NicknameWriteConverter().convert(Nickname("tester"))))
            .isEqualTo(Nickname("tester"))
        assertThat(BojIdReadConverter().convert(BojIdWriteConverter().convert(BojId("boj_user"))))
            .isEqualTo(BojId("boj_user"))
        assertThat(LogTitleReadConverter().convert(LogTitleWriteConverter().convert(LogTitle("제목"))))
            .isEqualTo(LogTitle("제목"))
        assertThat(LogContentReadConverter().convert(LogContentWriteConverter().convert(LogContent("내용"))))
            .isEqualTo(LogContent("내용"))
        assertThat(LogCodeReadConverter().convert(LogCodeWriteConverter().convert(LogCode("print(1)"))))
            .isEqualTo(LogCode("print(1)"))
        assertThat(AiReviewReadConverter().convert(AiReviewWriteConverter().convert(AiReview("리뷰"))))
            .isEqualTo(AiReview("리뷰"))
    }

    @Test
    @DisplayName("읽기 변환은 Value Object 검증 규칙을 그대로 적용한다")
    fun `읽기 변환 검증`() {
        assertThatThrownBy { BojIdReadConverter().convert("bad id") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NicknameReadConverter().convert("a") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LogCodeReadConverter().convert(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SolvedAcTierLevelReadConverter().convert(32) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Tier::class)
    @DisplayName("티어는 enum 이름으로 저장되고 같은 티어로 복원된다")
    fun `티어 문자열 왕복 변환`(tier: Tier) {
        val stored = TierWriteConverter().convert(tier)

        assertThat(stored).isEqualTo(tier.name)
        assertThat(TierStringReadConverter().convert(stored)).isEqualTo(tier)
    }

    @Test
    @DisplayName("enum 이름이 아닌 레거시 티어 문자열은 원인과 함께 안내 예외를 던진다")
    fun `레거시 티어 문자열`() {
        assertThatThrownBy { TierStringReadConverter().convert("gold") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("레거시 데이터 감지")
            .hasMessageContaining("tier=gold")
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("정수 티어 레벨은 해당 레벨 구간의 티어로 변환한다")
    fun `정수 티어 레벨 변환`() {
        assertThat(TierIntegerReadConverter().convert(1)).isEqualTo(Tier.from(1))
        assertThat(TierIntegerReadConverter().convert(30)).isEqualTo(Tier.from(30))
        assertThatThrownBy { TierIntegerReadConverter().convert(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("문제 카테고리는 영문 표준명으로 저장되고 알 수 없는 이름은 UNKNOWN으로 읽는다")
    fun `문제 카테고리 변환`() {
        assertThat(ProblemCategoryWriteConverter().convert(ProblemCategory.DP)).isEqualTo("Dynamic Programming")
        assertThat(ProblemCategoryReadConverter().convert("Dynamic Programming")).isEqualTo(ProblemCategory.DP)
        assertThat(ProblemCategoryReadConverter().convert("DP")).isEqualTo(ProblemCategory.UNKNOWN)
    }

    @Test
    @DisplayName("Solved.ac 티어 레벨은 정수로 저장되고 복원된다")
    fun `Solved ac 티어 레벨 변환`() {
        assertThat(SolvedAcTierLevelWriteConverter().convert(SolvedAcTierLevel(17))).isEqualTo(17)
        assertThat(SolvedAcTierLevelReadConverter().convert(0)).isEqualTo(SolvedAcTierLevel(0))
    }
}
