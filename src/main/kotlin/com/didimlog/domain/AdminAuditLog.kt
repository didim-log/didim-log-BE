package com.didimlog.domain

import com.didimlog.domain.enums.AdminActionType
import java.time.LocalDateTime
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 관리자 작업 감사 로그
 * 중요한 관리자 작업을 기록하여 추적 가능성을 보장합니다.
 */
@Document(collection = "admin_audit_logs")
data class AdminAuditLog(
    @Id
    val id: String? = null,
    @Indexed
    val adminId: String,
    val action: AdminActionType,
    val details: String,
    val ipAddress: String,
    @Indexed
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        validateAdminId(adminId)
        validateDetails(details)
        validateIpAddress(ipAddress)
    }

    private fun validateAdminId(adminId: String) {
        require(adminId.isNotBlank()) { "관리자 ID는 필수입니다." }
    }

    private fun validateDetails(details: String) {
        require(details.isNotBlank()) { "작업 상세 정보는 필수입니다." }
        require(details.length <= 1000) { "작업 상세 정보는 1000자 이하여야 합니다." }
    }

    private fun validateIpAddress(ipAddress: String) {
        require(ipAddress.isNotBlank()) { "IP 주소는 필수입니다." }
    }
}


