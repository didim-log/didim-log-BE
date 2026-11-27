package com.didimlog.problem.application.collector

data class ProblemJobWorkerAttempt(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val ownerId: String,
    val attemptId: String,
    val attemptNumber: Long
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
