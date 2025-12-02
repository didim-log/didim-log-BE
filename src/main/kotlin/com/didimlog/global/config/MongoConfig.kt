package com.didimlog.global.config

import com.didimlog.global.config.mongo.*
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
        converters.add(TierWriteConverter())
        converters.add(ProblemCategoryWriteConverter())

        // Reading Converters (DB -> 객체)
        converters.add(NicknameReadConverter())
        converters.add(BojIdReadConverter())
        converters.add(TierStringReadConverter())
        converters.add(TierIntegerReadConverter())
        converters.add(ProblemCategoryReadConverter())

        return MongoCustomConversions(converters)
    }
}

