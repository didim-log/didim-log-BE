package com.didimlog.application.problem.collector

data class ProblemJobTargetManifest(
    val version: Int,
    val jobId: String,
    val jobType: ProblemJobType,
    val explicitIds: List<String> = emptyList(),
    val range: JobRange? = null
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class ProblemJobTargetManifestReference(
    val schemaVersion: Int,
    val sha256: String
)
