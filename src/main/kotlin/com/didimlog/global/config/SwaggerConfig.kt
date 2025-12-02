package com.didimlog.global.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI(Swagger) 문서를 설정하는 클래스.
 * API 문서의 기본 메타데이터(제목, 버전)와 서버 URL을 정의한다.
 * JWT Bearer 토큰 인증을 위한 SecurityScheme을 설정한다.
 */
@Configuration
class SwaggerConfig(
    @Value("\${app.server.url}")
    private val serverUrl: String
) {

    /**
     * DidimLog 서비스의 전역 OpenAPI 스펙을 정의한다.
     * Nginx 등 프록시 환경에서도 올바른 URL(https)을 가리키도록 서버 설정을 추가함.
     * JWT Bearer 토큰 인증을 위한 SecurityScheme과 SecurityRequirement를 설정함.
     */
    @Bean
    fun didimLogOpenAPI(): OpenAPI {
        val info = Info()
            .title("DidimLog API")
            .description("DidimLog 학습 관리 서비스 API 명세서")
            .version("v1.0.0")

        // 운영 환경 및 로컬 환경 서버 URL을 등록하여 Swagger UI에서 선택 가능하도록 설정
        val servers = listOf(
            Server().url(serverUrl).description("Production Server"),
            Server().url("http://localhost:8080").description("Local Server")
        )

        // JWT Bearer 토큰 인증을 위한 SecurityScheme 정의
        val securityScheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .`in`(SecurityScheme.In.HEADER)
            .name("Authorization")

        // 모든 API 요청에 대해 JWT 인증을 기본으로 적용
        val securityRequirement = SecurityRequirement().addList("Authorization")

        return OpenAPI()
            .info(info)
            .servers(servers)
            .components(
                io.swagger.v3.oas.models.Components()
                    .addSecuritySchemes("Authorization", securityScheme)
            )
            .addSecurityItem(securityRequirement)
    }
}
