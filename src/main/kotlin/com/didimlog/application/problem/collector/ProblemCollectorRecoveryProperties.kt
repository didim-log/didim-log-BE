package com.didimlog.application.problem.collector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.problem-collector.recovery")
data class ProblemCollectorRecoveryProperties(
    val failOrphanedJobsOnStartup: Boolean = false
)
