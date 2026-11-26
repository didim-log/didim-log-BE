package com.didimlog.notice.domain.repository

import com.didimlog.notice.domain.Notice
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository

interface NoticeRepository : MongoRepository<Notice, String> {
    fun findAllByOrderByIsPinnedDescCreatedAtDesc(pageable: Pageable): Page<Notice>
}













