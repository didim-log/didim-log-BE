package com.didimlog.problem.application.collector

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ProblemCollectorWorkerIdentity(
    val ownerId: String = UUID.randomUUID().toString()
)
