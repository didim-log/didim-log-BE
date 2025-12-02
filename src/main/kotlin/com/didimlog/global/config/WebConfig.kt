package com.didimlog.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 웹 관련 설정 클래스
 * CORS 설정을 포함한다.
 */
@Configuration
class WebConfig : WebMvcConfigurer {
    @Value("\${app.server.url}")
    private val serverUrl: String? = null

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(
                "http://localhost:3000",
                "http://localhost:5173",
                "https://*.web.app",
                "https://didim-log-fe.web.app",
                serverUrl
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }
}

