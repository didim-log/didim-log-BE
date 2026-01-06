package com.didimlog.global.config

import com.didimlog.global.interceptor.MaintenanceModeInterceptor
import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import com.didimlog.global.ratelimit.RateLimitInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 설정
 * 인터셉터를 등록한다.
 */
@Configuration
@Profile("!test")
class WebInterceptorConfig(
    private val performanceMonitoringInterceptorProvider: ObjectProvider<PerformanceMonitoringInterceptor>,
    private val maintenanceModeInterceptorProvider: ObjectProvider<MaintenanceModeInterceptor>,
    private val rateLimitInterceptorProvider: ObjectProvider<RateLimitInterceptor>
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 유지보수 모드 인터셉터 (가장 먼저 실행)
        maintenanceModeInterceptorProvider.ifAvailable { interceptor ->
            registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                    "/api/v1/admin/system/**", // 유지보수 모드 제어 API는 제외
                    "/api/v1/system/**" // 시스템 상태 조회 API는 제외 (Public)
                )
        }

        // Rate Limiting 인터셉터 (인증 API 보호)
        rateLimitInterceptorProvider.ifAvailable { interceptor ->
            registry.addInterceptor(interceptor)
                .addPathPatterns(
                    "/api/v1/auth/signup",
                    "/api/v1/auth/login",
                    "/api/v1/auth/find-account",
                    "/api/v1/auth/reset-password"
                )
        }

        performanceMonitoringInterceptorProvider.ifAvailable { interceptor ->
            registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
        }
    }
}


