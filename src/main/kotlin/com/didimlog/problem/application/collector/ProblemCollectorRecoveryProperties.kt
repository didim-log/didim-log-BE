package com.didimlog.problem.application.collector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.problem-collector.recovery")
data class ProblemCollectorRecoveryProperties(
    val failOrphanedJobsOnStartup: Boolean = false
)
