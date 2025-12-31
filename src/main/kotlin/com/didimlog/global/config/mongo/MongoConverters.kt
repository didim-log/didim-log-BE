package com.didimlog.global.config.mongo

import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter

/**
 * MongoDB 커스텀 컨버터 모음
 * Value Object와 Enum을 MongoDB에 저장/읽기 시 자동으로 변환한다.
 */

// ========== Writing Converters (객체 -> DB) ==========

/**
 * Nickname Value Object를 String으로 변환
 */
@WritingConverter
class NicknameWriteConverter : Converter<Nickname, String> {
    override fun convert(source: Nickname): String {
        return source.value
    }
}

/**
 * BojId Value Object를 String으로 변환
 */
@WritingConverter
class BojIdWriteConverter : Converter<BojId, String> {
    override fun convert(source: BojId): String {
        return source.value
    }
}

@WritingConverter
class LogTitleWriteConverter : Converter<LogTitle, String> {
    override fun convert(source: LogTitle): String {
        return source.value
    }
}

@WritingConverter
class LogContentWriteConverter : Converter<LogContent, String> {
    override fun convert(source: LogContent): String {
        return source.value
    }
}

@WritingConverter
class LogCodeWriteConverter : Converter<LogCode, String> {
    override fun convert(source: LogCode): String {
        return source.value
    }
}

@WritingConverter
class AiReviewWriteConverter : Converter<AiReview, String> {
    override fun convert(source: AiReview): String {
        return source.value
    }
}

/**
 * Tier Enum을 String으로 변환 (DB에 enum 이름으로 저장)
 */
@WritingConverter
class TierWriteConverter : Converter<Tier, String> {
    override fun convert(source: Tier): String {
        return source.name
    }
}

/**
 * ProblemCategory Enum을 String으로 변환 (DB에 englishName으로 저장)
 */
@WritingConverter
class ProblemCategoryWriteConverter : Converter<ProblemCategory, String> {
    override fun convert(source: ProblemCategory): String {
        return source.englishName
    }
}

// ========== Reading Converters (DB -> 객체) ==========

/**
 * String을 Nickname Value Object로 변환
 */
@ReadingConverter
class NicknameReadConverter : Converter<String, Nickname> {
    override fun convert(source: String): Nickname {
        return Nickname(source)
    }
}

/**
 * String을 BojId Value Object로 변환
 */
@ReadingConverter
class BojIdReadConverter : Converter<String, BojId> {
    override fun convert(source: String): BojId {
        return BojId(source)
    }
}

@ReadingConverter
class LogTitleReadConverter : Converter<String, LogTitle> {
    override fun convert(source: String): LogTitle {
        return LogTitle(source)
    }
}

@ReadingConverter
class LogContentReadConverter : Converter<String, LogContent> {
    override fun convert(source: String): LogContent {
        return LogContent(source)
    }
}

@ReadingConverter
class LogCodeReadConverter : Converter<String, LogCode> {
    override fun convert(source: String): LogCode {
        return LogCode(source)
    }
}

@ReadingConverter
class AiReviewReadConverter : Converter<String, AiReview> {
    override fun convert(source: String): AiReview {
        return AiReview(source)
    }
}

/**
 * String을 Tier Enum으로 변환
 * DB에 저장된 값이 String(enum 이름)인 경우 사용
 */
@ReadingConverter
class TierStringReadConverter : Converter<String, Tier> {
    override fun convert(source: String): Tier {
        return try {
            Tier.valueOf(source)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "레거시 데이터 감지: 티어 형식이 올바르지 않습니다. " +
                    "tier=$source. DB Volume을 삭제하고 재시작하세요. (docker-compose down -v && docker-compose up -d)",
                e
            )
        }
    }
}

/**
 * Integer를 Tier Enum으로 변환
 * DB에 저장된 값이 Integer(레벨)인 경우 사용
 */
@ReadingConverter
class TierIntegerReadConverter : Converter<Int, Tier> {
    override fun convert(source: Int): Tier {
        return Tier.from(source)
    }
}

/**
 * String을 ProblemCategory Enum으로 변환
 * DB에 저장된 값이 String(englishName)인 경우 사용
 */
@ReadingConverter
class ProblemCategoryReadConverter : Converter<String, ProblemCategory> {
    override fun convert(source: String): ProblemCategory {
        return ProblemCategory.entries.find { it.englishName == source }
            ?: ProblemCategory.UNKNOWN
    }
}
