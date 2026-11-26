package com.didimlog.admin.api.dto

import com.didimlog.admin.domain.AdminActionType
import com.didimlog.admin.domain.AdminAuditLog
import java.time.LocalDateTime

/**
 * 관리자 작업 감사 로그 응답 DTO
 */
data class AdminAuditLogResponse(
    val id: String,
    val adminId: String,
    val action: AdminActionType,
    val details: String,
    val ipAddress: String,
    val createdAt: LocalDateTime
) {
    companion object {
        fun from(auditLog: AdminAuditLog): AdminAuditLogResponse {
            return AdminAuditLogResponse(
                id = auditLog.id ?: "",
                adminId = auditLog.adminId,
                action = auditLog.action,
                details = auditLog.details,
                ipAddress = auditLog.ipAddress,
                createdAt = auditLog.createdAt
            )
        }
    }
}













