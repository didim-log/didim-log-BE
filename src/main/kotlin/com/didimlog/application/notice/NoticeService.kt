package com.didimlog.application.notice

import com.didimlog.domain.Notice
import com.didimlog.domain.repository.NoticeRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 공지사항 관리 서비스
 */
@Service
class NoticeService(
    private val noticeRepository: NoticeRepository
) {

    /**
     * 공지사항을 작성한다.
     *
     * @param title 제목
     * @param content 내용
     * @param isPinned 상단 고정 여부
     * @return 저장된 공지사항
     */
    @Transactional
    fun createNotice(title: String, content: String, isPinned: Boolean = false): Notice {
        val notice = Notice(
            title = title,
            content = content,
            isPinned = isPinned
        )
        return noticeRepository.save(notice)
    }

    /**
     * 공지사항을 조회한다.
     *
     * @param noticeId 공지사항 ID
     * @return 공지사항
     * @throws BusinessException 공지사항을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getNotice(noticeId: String): Notice {
        return noticeRepository.findById(noticeId)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "공지사항을 찾을 수 없습니다. id=$noticeId")
            }
    }

    /**
     * 공지사항 목록을 조회한다.
     * 상단 고정 공지가 먼저 오고, 그 다음 최신순으로 정렬된다.
     *
     * @param pageable 페이징 정보
     * @return 공지사항 페이지
     */
    @Transactional(readOnly = true)
    fun getNotices(pageable: Pageable): Page<Notice> {
        return noticeRepository.findAllByOrderByIsPinnedDescCreatedAtDesc(pageable)
    }

    /**
     * 공지사항을 수정한다.
     *
     * @param noticeId 공지사항 ID
     * @param title 제목 (선택사항)
     * @param content 내용 (선택사항)
     * @param isPinned 상단 고정 여부 (선택사항)
     * @return 수정된 공지사항
     * @throws BusinessException 공지사항을 찾을 수 없는 경우
     */
    @Transactional
    fun updateNotice(
        noticeId: String,
        title: String? = null,
        content: String? = null,
        isPinned: Boolean? = null
    ): Notice {
        val notice = getNotice(noticeId)
        val updatedNotice = notice.update(title, content, isPinned)
        return noticeRepository.save(updatedNotice)
    }

    /**
     * 공지사항을 삭제한다.
     *
     * @param noticeId 공지사항 ID
     * @throws BusinessException 공지사항을 찾을 수 없는 경우
     */
    @Transactional
    fun deleteNotice(noticeId: String) {
        val notice = getNotice(noticeId)
        noticeRepository.delete(notice)
    }
}









