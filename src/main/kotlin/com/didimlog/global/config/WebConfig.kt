package com.didimlog.global.config

import com.didimlog.global.interceptor.MaintenanceModeInterceptor
import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 설정
 * 인터셉터를 등록한다.
 */
@Configuration
class WebConfig(
    private val performanceMonitoringInterceptorProvider: ObjectProvider<PerformanceMonitoringInterceptor>,
    private val maintenanceModeInterceptorProvider: ObjectProvider<MaintenanceModeInterceptor>
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 유지보수 모드 인터셉터 (가장 먼저 실행)
        maintenanceModeInterceptorProvider.ifAvailable { interceptor ->
            registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/admin/system/**") // 유지보수 모드 제어 API는 제외
        }

        performanceMonitoringInterceptorProvider.ifAvailable { interceptor ->
            registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
        }
    }
}
