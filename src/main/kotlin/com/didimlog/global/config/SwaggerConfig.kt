package com.didimlog.global.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI(Swagger) 문서를 설정하는 클래스.
 * API 문서의 기본 메타데이터(제목, 버전)와 서버 URL을 정의한다.
 */
@Configuration
class SwaggerConfig(
    @Value("\${app.server.url}")
    private val serverUrl: String
) {

    /**
     * DidimLog 서비스의 전역 OpenAPI 스펙을 정의한다.
     * Nginx 등 프록시 환경에서도 올바른 URL(https)을 가리키도록 서버 설정을 추가함.
     */
    @Bean
    fun didimLogOpenAPI(): OpenAPI {
        val info = Info()
            .title("DidimLog API")
            .description("DidimLog 학습 관리 서비스 API 명세서")
            .version("v1.0.0")

        // 운영 환경(HTTPS) URL을 명시적으로 추가하여 Swagger UI에서 호출 시 오류 방지
        val server = Server().url(serverUrl).description("DidimLog Server")

        return OpenAPI()
            .info(info)
            .addServersItem(server)
    }
}
