package com.didimlog.global.config

import com.didimlog.application.problem.collector.ProblemCollectorParallelProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * 비동기 처리 설정
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 5
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("async-")
        executor.initialize()
        return executor
    }

    @Bean(name = ["problemCrawlerExecutor"])
    fun problemCrawlerExecutor(properties: ProblemCollectorParallelProperties): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = properties.maxConcurrency
        executor.maxPoolSize = properties.maxConcurrency
        executor.queueCapacity = properties.maxConcurrency * 5
        executor.setThreadNamePrefix("problem-crawler-")
        executor.initialize()
        return executor
    }
}
