package com.didimlog.application.admin

import com.didimlog.domain.AdminAuditLog
import com.didimlog.domain.enums.AdminActionType
import com.didimlog.domain.repository.AdminAuditLogRepository
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

@DisplayName("AdminAuditService 테스트")
class AdminAuditServiceTest {

    private val adminAuditLogRepository: AdminAuditLogRepository = mockk()
    private val service = AdminAuditService(adminAuditLogRepository)

    private val pageable = PageRequest.of(0, 20)
    private val start = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val end = LocalDateTime.of(2026, 1, 31, 23, 59)
    private val action = AdminActionType.NOTICE_CREATE
    private val page = PageImpl(
        listOf(AdminAuditLog(adminId = "admin-1", action = action, details = "공지 생성", ipAddress = "127.0.0.1"))
    )

    @Test
    @DisplayName("관리자 작업을 감사 로그로 저장한다")
    fun `감사 로그 저장`() {
        val saved = slot<AdminAuditLog>()
        every { adminAuditLogRepository.save(capture(saved)) } answers { saved.captured }

        service.logAction("admin-1", action, "공지 생성", "10.0.0.1")

        assertThat(saved.captured.adminId).isEqualTo("admin-1")
        assertThat(saved.captured.action).isEqualTo(action)
        assertThat(saved.captured.details).isEqualTo("공지 생성")
        assertThat(saved.captured.ipAddress).isEqualTo("10.0.0.1")
    }

    @Test
    @DisplayName("감사 로그 저장 실패나 검증 실패는 호출자에게 전파하지 않는다")
    fun `감사 로그 저장 실패 무시`() {
        every { adminAuditLogRepository.save(any<AdminAuditLog>()) } throws IllegalStateException("db down")

        assertThatCode { service.logAction("admin-1", action, "공지 생성", "10.0.0.1") }
            .doesNotThrowAnyException()
        assertThatCode { service.logAction("admin-1", action, " ", "10.0.0.1") }
            .doesNotThrowAnyException()
        verify(exactly = 1) { adminAuditLogRepository.save(any<AdminAuditLog>()) }
    }

    @Test
    @DisplayName("감사 로그는 관리자 ID, 상세 정보, IP 주소를 검증한다")
    fun `감사 로그 도메인 검증`() {
        assertThatThrownBy { AdminAuditLog(adminId = " ", action = action, details = "d", ipAddress = "ip") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("관리자 ID는 필수입니다.")
        assertThatThrownBy { AdminAuditLog(adminId = "a", action = action, details = "x".repeat(1001), ipAddress = "ip") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("작업 상세 정보는 1000자 이하여야 합니다.")
        assertThatThrownBy { AdminAuditLog(adminId = "a", action = action, details = "d", ipAddress = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("IP 주소는 필수입니다.")
        assertThatCode { AdminAuditLog(adminId = "a", action = action, details = "x".repeat(1000), ipAddress = "ip") }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("단일 조건 조회 메서드는 대응하는 저장소 쿼리를 사용한다")
    fun `단일 조건 조회`() {
        every { adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable) } returns page
        every { adminAuditLogRepository.findByAdminIdOrderByCreatedAtDesc("admin-1", pageable) } returns page
        every { adminAuditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable) } returns page
        every { adminAuditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable) } returns page

        assertThat(service.getAuditLogs(pageable)).isSameAs(page)
        assertThat(service.getAuditLogsByAdminId("admin-1", pageable)).isSameAs(page)
        assertThat(service.getAuditLogsByAction(action, pageable)).isSameAs(page)
        assertThat(service.getAuditLogsByDateRange(start, end, pageable)).isSameAs(page)
    }

    @Test
    @DisplayName("관리자, 작업, 기간이 모두 있으면 세 조건을 함께 조회한다")
    fun `검색 전체 조건`() {
        every {
            adminAuditLogRepository.findByAdminIdAndActionAndCreatedAtBetweenOrderByCreatedAtDesc(
                "admin-1", action, start, end, pageable
            )
        } returns page

        assertThat(service.searchAuditLogs("admin-1", action, start, end, pageable)).isSameAs(page)
    }

    @Test
    @DisplayName("기간의 한쪽만 주어지면 기간 조건은 무시된다")
    fun `검색 기간 일부만 존재`() {
        every { adminAuditLogRepository.findByAdminIdAndActionOrderByCreatedAtDesc("admin-1", action, pageable) } returns page
        every { adminAuditLogRepository.findByAdminIdOrderByCreatedAtDesc("admin-1", pageable) } returns page
        every { adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable) } returns page

        service.searchAuditLogs("admin-1", action, start, null, pageable)
        service.searchAuditLogs("admin-1", null, null, end, pageable)
        service.searchAuditLogs(null, null, start, null, pageable)

        verify(exactly = 1) { adminAuditLogRepository.findByAdminIdAndActionOrderByCreatedAtDesc("admin-1", action, pageable) }
        verify(exactly = 1) { adminAuditLogRepository.findByAdminIdOrderByCreatedAtDesc("admin-1", pageable) }
        verify(exactly = 1) { adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable) }
        confirmVerified(adminAuditLogRepository)
    }

    @Test
    @DisplayName("관리자와 기간 또는 작업과 기간 조합을 각각의 저장소 쿼리로 조회한다")
    fun `검색 기간 조합`() {
        every {
            adminAuditLogRepository.findByAdminIdAndCreatedAtBetweenOrderByCreatedAtDesc("admin-1", start, end, pageable)
        } returns page
        every {
            adminAuditLogRepository.findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(action, start, end, pageable)
        } returns page
        every { adminAuditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable) } returns page

        service.searchAuditLogs("admin-1", null, start, end, pageable)
        service.searchAuditLogs(null, action, start, end, pageable)
        service.searchAuditLogs(null, null, start, end, pageable)

        verify(exactly = 1) {
            adminAuditLogRepository.findByAdminIdAndCreatedAtBetweenOrderByCreatedAtDesc("admin-1", start, end, pageable)
        }
        verify(exactly = 1) {
            adminAuditLogRepository.findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(action, start, end, pageable)
        }
        verify(exactly = 1) { adminAuditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable) }
        confirmVerified(adminAuditLogRepository)
    }

    @Test
    @DisplayName("작업 타입만 주어지면 작업 타입으로 조회한다")
    fun `검색 작업 타입만`() {
        every { adminAuditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable) } returns page

        assertThat(service.searchAuditLogs(null, action, null, null, pageable)).isSameAs(page)
    }
}
