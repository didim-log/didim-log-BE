package com.didimlog.application.problem.collector

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.problem-collector.worker-lease")
data class ProblemCollectorWorkerLeaseProperties(
    val enabled: Boolean = false,
    val leaseDuration: Duration = Duration.ofSeconds(90),
    val heartbeatInterval: Duration = Duration.ofSeconds(20),
    val scanInterval: Duration = Duration.ofSeconds(10)
) {
    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "problem collector worker lease duration must be positive"
        }
        require(leaseDuration.toMillis() > 0) {
            "problem collector worker lease duration must be at least one millisecond"
        }
        require(!heartbeatInterval.isZero && !heartbeatInterval.isNegative) {
            "problem collector worker heartbeat interval must be positive"
        }
        require(heartbeatInterval.toMillis() > 0) {
            "problem collector worker heartbeat interval must be at least one millisecond"
        }
        require(!scanInterval.isZero && !scanInterval.isNegative) {
            "problem collector worker scan interval must be positive"
        }
        require(scanInterval.toMillis() > 0) {
            "problem collector worker scan interval must be at least one millisecond"
        }
        require(leaseDuration >= heartbeatInterval.multipliedBy(3)) {
            "problem collector worker lease duration must be at least three times the heartbeat interval"
        }
    }
}
