package com.didimlog.infra.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@ConfigurationProperties(prefix = "ai.review.async")
data class AiReviewAsyncProperties(
    val corePoolSize: Int = 2,
    val maxPoolSize: Int = 4,
    val queueCapacity: Int = 200
)

@Configuration
@EnableConfigurationProperties(AiReviewAsyncProperties::class)
class AiReviewAsyncConfig {

    @Bean("aiReviewTaskExecutor")
    fun aiReviewTaskExecutor(properties: AiReviewAsyncProperties): TaskExecutor {
        val corePoolSize = properties.corePoolSize.coerceAtLeast(1)
        val maxPoolSize = properties.maxPoolSize.coerceAtLeast(corePoolSize)
        val queueCapacity = properties.queueCapacity.coerceAtLeast(0)

        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = corePoolSize
        executor.maxPoolSize = maxPoolSize
        executor.queueCapacity = queueCapacity
        executor.setThreadNamePrefix("ai-review-")
        executor.setWaitForTasksToCompleteOnShutdown(false)
        executor.initialize()
        return executor
    }
}
