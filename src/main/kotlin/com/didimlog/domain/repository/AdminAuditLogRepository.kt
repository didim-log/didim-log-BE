package com.didimlog.domain.repository

import com.didimlog.domain.AdminAuditLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.LocalDateTime

interface AdminAuditLogRepository : MongoRepository<AdminAuditLog, String> {

    fun findByAdminIdOrderByCreatedAtDesc(adminId: String, pageable: Pageable): Page<AdminAuditLog>

    fun findByActionOrderByCreatedAtDesc(action: com.didimlog.domain.enums.AdminActionType, pageable: Pageable): Page<AdminAuditLog>

    fun findByCreatedAtBetweenOrderByCreatedAtDesc(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        pageable: Pageable
    ): Page<AdminAuditLog>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AdminAuditLog>
}












