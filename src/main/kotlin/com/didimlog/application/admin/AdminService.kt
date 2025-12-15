package com.didimlog.application.admin

import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AdminUserResponse
import com.didimlog.ui.dto.AdminUserUpdateDto
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(AdminService::class.java)

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

    /**
     * 관리자 사용자 정보 강제 수정 (Dynamic Update)
     * - 요청된 필드만 업데이트한다.
     * - 변경 전/후 값을 로그로 남긴다. (관리자 작업 이력)
     */
    @Transactional
    fun updateUser(studentId: String, request: AdminUserUpdateDto): Student {
        val student = studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=$studentId")
            }

        val before = toAuditSnapshot(student)
        var updatedStudent = student
        var isChanged = false

        val newRole = parseRoleOrNull(request.role)
        if (newRole != null && student.role != newRole) {
            updatedStudent = updatedStudent.copy(role = newRole)
            isChanged = true
        }

        val newNickname = normalizeTextOrNull(request.nickname)
        if (newNickname != null && student.nickname.value != newNickname) {
            val nicknameVo = Nickname(newNickname)
            if (studentRepository.existsByNickname(nicknameVo)) {
                throw BusinessException(ErrorCode.DUPLICATE_NICKNAME, "이미 사용 중인 닉네임입니다. nickname=$newNickname")
            }
            updatedStudent = updatedStudent.copy(nickname = nicknameVo)
            isChanged = true
        }

        val newBojId = normalizeTextOrNull(request.bojId)
        if (newBojId != null) {
            val bojIdVo = BojId(newBojId)
            val currentBojId = student.bojId?.value
            if (currentBojId != bojIdVo.value) {
                if (studentRepository.existsByBojId(bojIdVo.value)) {
                    throw IllegalArgumentException("이미 존재하는 BOJ ID입니다.")
                }
                updatedStudent = updatedStudent.copy(bojId = bojIdVo)
                isChanged = true
            }
        }

        if (!isChanged) {
            log.info("관리자 사용자 강제 수정: 변경 없음. before={}", before)
            return student
        }

        val saved = studentRepository.save(updatedStudent)
        val after = toAuditSnapshot(saved)

        log.info("관리자 사용자 강제 수정 완료. before={}, after={}", before, after)
        return saved
    }

    private fun normalizeTextOrNull(value: String?): String? {
        if (value == null) {
            return null
        }
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "공백 값은 허용되지 않습니다.")
        }
        return trimmed
    }

    private fun parseRoleOrNull(role: String?): Role? {
        if (role == null) {
            return null
        }

        val normalized = role.trim().uppercase()
        val value = if (normalized.startsWith("ROLE_")) {
            normalized.removePrefix("ROLE_")
        } else {
            normalized
        }

        val parsed = Role.from(value)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 권한입니다. role=$role")

        if (parsed != Role.USER && parsed != Role.ADMIN) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "허용되지 않은 권한입니다. role=$role")
        }

        return parsed
    }

    private fun toAuditSnapshot(student: Student): String {
        val bojId = student.bojId?.value
        return "studentId=${student.id}, provider=${student.provider.value}, providerId=${student.providerId}, role=${student.role.value}, nickname=${student.nickname.value}, bojId=$bojId"
    }
}
