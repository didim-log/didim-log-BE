package com.didimlog.admin.application

import com.didimlog.admin.domain.AdminActionType
import com.didimlog.admin.domain.AdminAuditLog
import com.didimlog.admin.domain.repository.AdminAuditLogRepository
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

@DisplayName("AdminAuditService 감사 로그 검색 조건 분기 테스트")
class AdminAuditServiceSearchTest {

    private val repository: AdminAuditLogRepository = mockk()
    private val service = AdminAuditService(repository)
    private val pageable = PageRequest.of(0, 20)
    private val page: Page<AdminAuditLog> = PageImpl(emptyList())
    private val start = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val end = LocalDateTime.of(2026, 1, 31, 23, 59)
    private val action = AdminActionType.values().first()

    @Test
    @DisplayName("관리자, 작업, 기간이 모두 있으면 세 조건으로 조회한다")
    fun `admin action and date range`() {
        every {
            repository.findByAdminIdAndActionAndCreatedAtBetweenOrderByCreatedAtDesc(ADMIN_ID, action, start, end, pageable)
        } returns page

        assertThat(service.searchAuditLogs(ADMIN_ID, action, start, end, pageable)).isSameAs(page)

        verify(exactly = 1) {
            repository.findByAdminIdAndActionAndCreatedAtBetweenOrderByCreatedAtDesc(ADMIN_ID, action, start, end, pageable)
        }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("관리자와 작업만 있으면 기간 없이 조회한다")
    fun `admin and action`() {
        every { repository.findByAdminIdAndActionOrderByCreatedAtDesc(ADMIN_ID, action, pageable) } returns page

        assertThat(service.searchAuditLogs(ADMIN_ID, action, null, null, pageable)).isSameAs(page)

        verify(exactly = 1) { repository.findByAdminIdAndActionOrderByCreatedAtDesc(ADMIN_ID, action, pageable) }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("기간의 한쪽만 있으면 기간 조건을 무시한다")
    fun `partial date range is ignored`() {
        every { repository.findByAdminIdAndActionOrderByCreatedAtDesc(ADMIN_ID, action, pageable) } returns page
        every { repository.findByAdminIdOrderByCreatedAtDesc(ADMIN_ID, pageable) } returns page
        every { repository.findByActionOrderByCreatedAtDesc(action, pageable) } returns page
        every { repository.findAllByOrderByCreatedAtDesc(pageable) } returns page

        service.searchAuditLogs(ADMIN_ID, action, start, null, pageable)
        service.searchAuditLogs(ADMIN_ID, null, null, end, pageable)
        service.searchAuditLogs(null, action, start, null, pageable)
        service.searchAuditLogs(null, null, null, end, pageable)

        verify(exactly = 1) { repository.findByAdminIdAndActionOrderByCreatedAtDesc(ADMIN_ID, action, pageable) }
        verify(exactly = 1) { repository.findByAdminIdOrderByCreatedAtDesc(ADMIN_ID, pageable) }
        verify(exactly = 1) { repository.findByActionOrderByCreatedAtDesc(action, pageable) }
        verify(exactly = 1) { repository.findAllByOrderByCreatedAtDesc(pageable) }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("관리자와 기간만 있으면 두 조건으로 조회한다")
    fun `admin and date range`() {
        every {
            repository.findByAdminIdAndCreatedAtBetweenOrderByCreatedAtDesc(ADMIN_ID, start, end, pageable)
        } returns page

        assertThat(service.searchAuditLogs(ADMIN_ID, null, start, end, pageable)).isSameAs(page)

        verify(exactly = 1) {
            repository.findByAdminIdAndCreatedAtBetweenOrderByCreatedAtDesc(ADMIN_ID, start, end, pageable)
        }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("작업과 기간만 있으면 두 조건으로 조회한다")
    fun `action and date range`() {
        every {
            repository.findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(action, start, end, pageable)
        } returns page

        assertThat(service.searchAuditLogs(null, action, start, end, pageable)).isSameAs(page)

        verify(exactly = 1) {
            repository.findByActionAndCreatedAtBetweenOrderByCreatedAtDesc(action, start, end, pageable)
        }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("관리자만 있으면 관리자 조건으로 조회한다")
    fun `admin only`() {
        every { repository.findByAdminIdOrderByCreatedAtDesc(ADMIN_ID, pageable) } returns page

        assertThat(service.searchAuditLogs(ADMIN_ID, null, null, null, pageable)).isSameAs(page)

        verify(exactly = 1) { repository.findByAdminIdOrderByCreatedAtDesc(ADMIN_ID, pageable) }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("작업만 있으면 작업 조건으로 조회한다")
    fun `action only`() {
        every { repository.findByActionOrderByCreatedAtDesc(action, pageable) } returns page

        assertThat(service.searchAuditLogs(null, action, null, null, pageable)).isSameAs(page)

        verify(exactly = 1) { repository.findByActionOrderByCreatedAtDesc(action, pageable) }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("기간만 있으면 기간 조건으로 조회한다")
    fun `date range only`() {
        every { repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable) } returns page

        assertThat(service.searchAuditLogs(null, null, start, end, pageable)).isSameAs(page)

        verify(exactly = 1) { repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable) }
        confirmVerified(repository)
    }

    @Test
    @DisplayName("조건이 없으면 전체를 최신순으로 조회한다")
    fun `no condition`() {
        every { repository.findAllByOrderByCreatedAtDesc(pageable) } returns page

        assertThat(service.searchAuditLogs(null, null, null, null, pageable)).isSameAs(page)

        verify(exactly = 1) { repository.findAllByOrderByCreatedAtDesc(pageable) }
        confirmVerified(repository)
    }

    companion object {
        private const val ADMIN_ID = "admin-1"
    }
}
