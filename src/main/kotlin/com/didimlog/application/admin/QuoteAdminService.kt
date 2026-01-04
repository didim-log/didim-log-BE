package com.didimlog.application.admin

import com.didimlog.domain.Quote
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자용 명언 관리 서비스
 */
@Service
class QuoteAdminService(
    private val quoteRepository: QuoteRepository
) {

    /**
     * 명언 목록을 페이징하여 조회한다.
     *
     * @param pageable 페이징 정보
     * @return 명언 목록 페이지
     */
    @Transactional(readOnly = true)
    fun getAllQuotes(pageable: Pageable): Page<Quote> {
        return quoteRepository.findAll(pageable)
    }

    /**
     * 새로운 명언을 추가한다.
     *
     * @param content 명언 내용
     * @param author 저자명
     * @return 저장된 명언
     */
    @Transactional
    fun createQuote(content: String, author: String): Quote {
        val quote = Quote(
            content = content,
            author = author.ifBlank { "Unknown" }
        )
        return quoteRepository.save(quote)
    }

    /**
     * 명언을 삭제한다.
     *
     * @param id 명언 ID
     * @throws BusinessException 명언을 찾을 수 없는 경우
     */
    @Transactional
    fun deleteQuote(id: String) {
        val quote = quoteRepository.findById(id)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "명언을 찾을 수 없습니다. id=$id")
            }
        quoteRepository.delete(quote)
    }
}











