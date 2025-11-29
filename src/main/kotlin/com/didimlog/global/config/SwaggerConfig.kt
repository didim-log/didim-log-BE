package com.didimlog.global.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI(Swagger) 문서를 설정하는 클래스.
 * API 문서의 기본 메타데이터(제목, 버전 등)를 정의한다.
 */
@Configuration
class SwaggerConfig {

    /**
     * DidimLog 서비스의 전역 OpenAPI 스펙을 정의한다.
     */
    @Bean
    fun didimLogOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("DidimLog API")
                    .version("v1")
            )
    }
}


