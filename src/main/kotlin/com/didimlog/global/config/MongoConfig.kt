package com.didimlog.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.config.EnableMongoAuditing

/**
 * MongoDB 감사(Auditing) 기능을 활성화하는 설정 클래스.
 * 생성/수정 시간 등의 메타데이터를 자동으로 관리할 수 있도록 한다.
 */
@Configuration
@EnableMongoAuditing
class MongoConfig


