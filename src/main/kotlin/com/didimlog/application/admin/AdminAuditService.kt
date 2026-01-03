package com.didimlog.application.admin

import com.didimlog.domain.AdminAuditLog
import com.didimlog.domain.enums.AdminActionType
import com.didimlog.domain.repository.AdminAuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 관리자 작업 감사 로그 서비스
 * 중요한 관리자 작업을 기록하여 추적 가능성을 보장합니다.
 */
@Service
class AdminAuditService(
    private val adminAuditLogRepository: AdminAuditLogRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 관리자 작업을 로깅합니다.
     * 비동기로 실행되어 메인 트랜잭션을 블로킹하지 않습니다.
     *
     * @param adminId 관리자 ID
     * @param action 작업 타입
     * @param details 작업 상세 정보
     * @param ipAddress 클라이언트 IP 주소
     */
    @Async
    @Transactional
    fun logAction(
        adminId: String,
        action: AdminActionType,
        details: String,
        ipAddress: String
    ) {
        try {
            val auditLog = AdminAuditLog(
                adminId = adminId,
                action = action,
                details = details,
                ipAddress = ipAddress
            )
            adminAuditLogRepository.save(auditLog)
            log.debug("Admin audit log saved: adminId={}, action={}", adminId, action)
        } catch (e: Exception) {
            log.error("Failed to save admin audit log: adminId={}, action={}", adminId, action, e)
        }
    }

    /**
     * 관리자 작업 로그를 조회합니다.
     *
     * @param pageable 페이지 정보
     * @return 관리자 작업 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getAuditLogs(pageable: Pageable): Page<AdminAuditLog> {
        return adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
    }

    /**
     * 특정 관리자의 작업 로그를 조회합니다.
     *
     * @param adminId 관리자 ID
     * @param pageable 페이지 정보
     * @return 관리자 작업 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getAuditLogsByAdminId(adminId: String, pageable: Pageable): Page<AdminAuditLog> {
        return adminAuditLogRepository.findByAdminIdOrderByCreatedAtDesc(adminId, pageable)
    }

    /**
     * 특정 작업 타입의 로그를 조회합니다.
     *
     * @param action 작업 타입
     * @param pageable 페이지 정보
     * @return 관리자 작업 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getAuditLogsByAction(action: AdminActionType, pageable: Pageable): Page<AdminAuditLog> {
        return adminAuditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable)
    }

    /**
     * 특정 기간의 로그를 조회합니다.
     *
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @param pageable 페이지 정보
     * @return 관리자 작업 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getAuditLogsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        pageable: Pageable
    ): Page<AdminAuditLog> {
        return adminAuditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate, pageable)
    }
}



