package com.didimlog.application.problem.collector

data class JobPageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
) {
    companion object {
        fun <T> of(content: List<T>, page: Int, size: Int, totalElements: Long): JobPageResponse<T> {
            val safeSize = if (size <= 0) 1 else size
            val totalPages = if (totalElements == 0L) 0 else ((totalElements + safeSize - 1) / safeSize).toInt()
            return JobPageResponse(
                content = content,
                page = page,
                size = size,
                totalElements = totalElements,
                totalPages = totalPages,
                hasNext = page < totalPages,
                hasPrevious = page > 1
            )
        }
    }
}
