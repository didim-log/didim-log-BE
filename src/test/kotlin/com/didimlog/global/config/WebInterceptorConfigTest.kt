package com.didimlog.global.config

import com.didimlog.global.ratelimit.RateLimitInterceptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.config.annotation.InterceptorRegistration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import java.util.function.Consumer

@DisplayName("웹 인터셉터 설정 테스트")
class WebInterceptorConfigTest {

    @Test
    @DisplayName("Rate Limit 인터셉터를 전체 인증 경로에 등록한다")
    fun `registers rate limit interceptor for auth routes`() {
        val rateLimitInterceptor = mockk<RateLimitInterceptor>()
        val registration = mockk<InterceptorRegistration>(relaxed = true)
        val registry = mockk<InterceptorRegistry>()
        every { registry.addInterceptor(rateLimitInterceptor) } returns registration

        val rateLimitProvider = mockk<ObjectProvider<RateLimitInterceptor>>()
        every { rateLimitProvider.ifAvailable(any()) } answers {
            firstArg<Consumer<RateLimitInterceptor>>().accept(rateLimitInterceptor)
        }
        val config = WebInterceptorConfig(
            performanceMonitoringInterceptorProvider = unavailableProvider(),
            maintenanceModeInterceptorProvider = unavailableProvider(),
            rateLimitInterceptorProvider = rateLimitProvider
        )

        config.addInterceptors(registry)

        verify(exactly = 1) {
            registration.addPathPatterns("/api/v1/auth/**")
        }
    }

    private inline fun <reified T : Any> unavailableProvider(): ObjectProvider<T> {
        return mockk<ObjectProvider<T>>().also { provider ->
            every { provider.ifAvailable(any()) } answers { Unit }
        }
    }
}
