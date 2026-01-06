package com.didimlog.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 설정
 *
 * CORS 허용 Origin을 환경 변수 기반으로 동적으로 구성한다.
 * - 설정 키: cors.allowed-origins
 * - 환경 변수: ALLOWED_ORIGINS
 * - 기본값(로컬): http://localhost:5173,http://localhost:8080
 */
@Configuration
class WebConfig(
    @Value("\${cors.allowed-origins:http://localhost:5173,http://localhost:8080}")
    private val allowedOrigins: String
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        val origins = parseAllowedOrigins(allowedOrigins)
        registry.addMapping("/**")
            .allowedOrigins(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }

    internal fun parseAllowedOrigins(value: String): List<String> {
        return value.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
