package com.didimlog.application.problem.collector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.problem-collector.parallel")
data class ProblemCollectorParallelProperties(
    val enabled: Boolean = false,
    val maxConcurrency: Int = 1
) {
    init {
        require(maxConcurrency in 1..MAX_CONCURRENCY) {
            "problem collector max concurrency must be between 1 and $MAX_CONCURRENCY"
        }
    }

    val windowSize: Int
        get() = if (enabled) maxConcurrency else 1

    private companion object {
        const val MAX_CONCURRENCY = 16
    }
}
