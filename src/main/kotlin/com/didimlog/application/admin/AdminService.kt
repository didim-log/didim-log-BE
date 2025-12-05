package com.didimlog.application.admin

import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AdminUserResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 서비스
 * 관리자 권한이 필요한 기능들을 제공한다.
 */
@Service
class AdminService(
    private val studentRepository: StudentRepository,
    private val quoteRepository: QuoteRepository
) {

    /**
     * 전체 회원 목록을 페이징하여 조회한다.
     * Student 엔티티를 AdminUserResponse DTO로 변환하여 반환한다.
     *
     * @param pageable 페이징 정보
     * @return 회원 목록 페이지 (AdminUserResponse DTO)
     */
    @Transactional(readOnly = true)
    fun getAllUsers(pageable: Pageable): Page<AdminUserResponse> {
        return studentRepository.findAll(pageable).map { AdminUserResponse.from(it) }
    }

    /**
     * 특정 회원을 강제 탈퇴시킨다.
     *
     * @param studentId 학생 ID
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional
    fun deleteUser(studentId: String) {
        val student = studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=$studentId")
            }
        
        studentRepository.delete(student)
    }

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
        val quote = Quote(content = content, author = author)
        return quoteRepository.save(quote)
    }

    /**
     * 명언을 삭제한다.
     *
     * @param quoteId 명언 ID
     * @throws BusinessException 명언을 찾을 수 없는 경우
     */
    @Transactional
    fun deleteQuote(quoteId: String) {
        val quote = quoteRepository.findById(quoteId)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "명언을 찾을 수 없습니다. quoteId=$quoteId")
            }
        
        quoteRepository.delete(quote)
    }
}
