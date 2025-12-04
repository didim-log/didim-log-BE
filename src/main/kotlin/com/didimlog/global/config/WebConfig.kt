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

    @Value("\${app.cors.allowed-origins}")
    private lateinit var allowedOriginsString: String

    override fun addCorsMappings(registry: CorsRegistry) {
        val allowedOrigins = allowedOriginsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        registry.addMapping("/api/**")
            .allowedOriginPatterns(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }
}

