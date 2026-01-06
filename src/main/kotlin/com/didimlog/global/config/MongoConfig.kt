package com.didimlog.global.config

import com.didimlog.global.config.mongo.BojIdReadConverter
import com.didimlog.global.config.mongo.BojIdWriteConverter
import com.didimlog.global.config.mongo.AiReviewReadConverter
import com.didimlog.global.config.mongo.AiReviewWriteConverter
import com.didimlog.global.config.mongo.LogCodeReadConverter
import com.didimlog.global.config.mongo.LogCodeWriteConverter
import com.didimlog.global.config.mongo.LogContentReadConverter
import com.didimlog.global.config.mongo.LogContentWriteConverter
import com.didimlog.global.config.mongo.LogTitleReadConverter
import com.didimlog.global.config.mongo.LogTitleWriteConverter
import com.didimlog.global.config.mongo.NicknameReadConverter
import com.didimlog.global.config.mongo.NicknameWriteConverter
import com.didimlog.global.config.mongo.ProblemCategoryReadConverter
import com.didimlog.global.config.mongo.ProblemCategoryWriteConverter
import com.didimlog.global.config.mongo.SolvedAcTierLevelReadConverter
import com.didimlog.global.config.mongo.SolvedAcTierLevelWriteConverter
import com.didimlog.global.config.mongo.TierIntegerReadConverter
import com.didimlog.global.config.mongo.TierStringReadConverter
import com.didimlog.global.config.mongo.TierWriteConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.mongodb.config.EnableMongoAuditing
import org.springframework.data.mongodb.core.convert.MongoCustomConversions

/**
 * MongoDB 설정 클래스
 * - 감사(Auditing) 기능 활성화
 * - Value Object 및 Enum 타입 변환을 위한 커스텀 컨버터 등록
 */
@Configuration
@EnableMongoAuditing
class MongoConfig {

    /**
     * MongoDB 커스텀 컨버터를 등록한다.
     * Value Object(Nickname, BojId)와 Enum(Tier)을 자동으로 변환한다.
     */
    @Bean
    fun mongoCustomConversions(): MongoCustomConversions {
        val converters = mutableListOf<Converter<*, *>>()

        // Writing Converters (객체 -> DB)
        converters.add(NicknameWriteConverter())
        converters.add(BojIdWriteConverter())
        converters.add(LogTitleWriteConverter())
        converters.add(LogContentWriteConverter())
        converters.add(LogCodeWriteConverter())
        converters.add(AiReviewWriteConverter())
        converters.add(TierWriteConverter())
        converters.add(ProblemCategoryWriteConverter())
        converters.add(SolvedAcTierLevelWriteConverter())

        // Reading Converters (DB -> 객체)
        converters.add(NicknameReadConverter())
        converters.add(BojIdReadConverter())
        converters.add(LogTitleReadConverter())
        converters.add(LogContentReadConverter())
        converters.add(LogCodeReadConverter())
        converters.add(AiReviewReadConverter())
        converters.add(TierStringReadConverter())
        converters.add(TierIntegerReadConverter())
        converters.add(ProblemCategoryReadConverter())
        converters.add(SolvedAcTierLevelReadConverter())

        return MongoCustomConversions(converters)
    }
}
