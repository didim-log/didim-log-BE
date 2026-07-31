package com.didimlog.application.problem.collector

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

const val PROBLEM_COLLECTOR_HEARTBEAT_EXECUTOR = "problemCollectorHeartbeatExecutor"

@Configuration
class ProblemCollectorWorkerLeaseConfig {

    @Bean(name = [PROBLEM_COLLECTOR_HEARTBEAT_EXECUTOR], destroyMethod = "shutdown")
    fun problemCollectorHeartbeatExecutor(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "problem-collector-heartbeat").apply {
                isDaemon = true
            }
        }
    }
}
