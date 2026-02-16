package com.didimlog.application.notice

import com.didimlog.domain.Notice
import com.didimlog.domain.repository.NoticeRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.util.Optional

@DisplayName("NoticeService 테스트")
class NoticeServiceTest {

    private val noticeRepository: NoticeRepository = mockk()
    private val noticeService = NoticeService(noticeRepository)

    @Test
    fun `공지 생성 성공`() {
        every { noticeRepository.save(any()) } answers { firstArg() }

        val result = noticeService.createNotice("제목", "내용", isPinned = true)

        assertThat(result.title).isEqualTo("제목")
        assertThat(result.content).isEqualTo("내용")
        assertThat(result.isPinned).isTrue()
        verify(exactly = 1) { noticeRepository.save(any()) }
    }

    @Test
    fun `단건 조회 실패 시 BusinessException`() {
        every { noticeRepository.findById("n-1") } returns Optional.empty()

        assertThatThrownBy { noticeService.getNotice("n-1") }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.COMMON_RESOURCE_NOT_FOUND }
    }

    @Test
    fun `목록 조회는 고정공지 우선 정렬 조회 메서드를 호출한다`() {
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl(listOf(Notice(title = "공지", content = "내용")))
        every { noticeRepository.findAllByOrderByIsPinnedDescCreatedAtDesc(pageable) } returns page

        val result = noticeService.getNotices(pageable)

        assertThat(result.totalElements).isEqualTo(1)
        verify(exactly = 1) { noticeRepository.findAllByOrderByIsPinnedDescCreatedAtDesc(pageable) }
    }

    @Test
    fun `공지 수정 성공`() {
        val existing = Notice(id = "n-1", title = "old", content = "old-content")
        every { noticeRepository.findById("n-1") } returns Optional.of(existing)
        every { noticeRepository.save(any()) } answers { firstArg() }

        val updated = noticeService.updateNotice("n-1", title = "new", content = "new-content", isPinned = true)

        assertThat(updated.title).isEqualTo("new")
        assertThat(updated.content).isEqualTo("new-content")
        assertThat(updated.isPinned).isTrue()
        verify(exactly = 1) { noticeRepository.save(any()) }
    }

    @Test
    fun `공지 삭제 성공`() {
        val existing = Notice(id = "n-1", title = "old", content = "old-content")
        every { noticeRepository.findById("n-1") } returns Optional.of(existing)
        every { noticeRepository.delete(existing) } returns Unit

        noticeService.deleteNotice("n-1")

        verify(exactly = 1) { noticeRepository.delete(existing) }
    }
}

