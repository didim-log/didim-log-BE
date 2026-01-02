package com.didimlog.ui.dto

/**
 * 시스템 상태 응답 DTO
 */
data class SystemStatusResponse(
    val underMaintenance: Boolean,
    val maintenanceMessage: String? = null
)


