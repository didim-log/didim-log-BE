package com.didimlog.application.admin

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.student.AccountDeletionService
import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.SensitiveDataMasker
import com.didimlog.ui.dto.AdminUserResponse
import com.didimlog.ui.dto.AdminUserUpdateDto
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 관리자 서비스
 * 관리자 권한이 필요한 기능들을 제공한다.
 */
@Service
class AdminService(
    private val studentRepository: StudentRepository,
    private val quoteRepository: QuoteRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val credentialSessionCoordinator: CredentialSessionCoordinator,
    private val accountDeletionService: AccountDeletionService
) {

    private val log = LoggerFactory.getLogger(AdminService::class.java)

    /**
     * 전체 회원 목록을 페이징하여 조회한다.
     * 검색어와 날짜 범위 필터를 지원한다.
     * Student 엔티티를 AdminUserResponse DTO로 변환하여 반환한다.
     *
     * @param pageable 페이징 정보
     * @param search 검색어 (닉네임, BOJ ID, 이메일, null 가능)
     * @param startDate 가입 시작일 (ISO 8601 형식, null 가능)
     * @param endDate 가입 종료일 (ISO 8601 형식, null 가능)
     * @return 회원 목록 페이지 (AdminUserResponse DTO)
     */
    @Transactional(readOnly = true)
    fun getAllUsers(
        pageable: Pageable,
        search: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): Page<AdminUserResponse> {
        val createdAtFrom = startDate
            ?.takeUnless(String::isBlank)
            ?.let { parseDate(it).atStartOfDay() }
        val createdAtTo = endDate
            ?.takeUnless(String::isBlank)
            ?.let { parseDate(it).atTime(23, 59, 59) }
        val studentPage = studentRepository.searchAdminUsers(
            pageable = pageable,
            search = search,
            createdAtFrom = createdAtFrom,
            createdAtTo = createdAtTo
        )
        val studentIds = studentPage.content.mapNotNull { it.id }.toSet()
        val retrospectiveCounts = if (studentIds.isEmpty()) {
            emptyMap()
        } else {
            retrospectiveRepository.countByStudentIds(studentIds)
        }

        return studentPage.map { student ->
            val solvedCount = calculateSolvedCount(student)
            val retrospectiveCount = student.id?.let { retrospectiveCounts[it] } ?: 0L
            AdminUserResponse.from(student, solvedCount, retrospectiveCount)
        }
    }

    /**
     * 학생이 해결한 문제 수를 계산한다.
     * SUCCESS인 Solution 중 고유한 problemId의 개수를 반환한다.
     *
     * @param student 학생
     * @return 해결한 고유한 문제 수
     */
    private fun calculateSolvedCount(student: Student): Long {
        return student.solutions.getAll()
            .filter { it.isSuccess() }
            .map { it.problemId.value }
            .distinct()
            .size
            .toLong()
    }

    /**
     * 특정 회원을 강제 탈퇴시킨다.
     *
     * @param studentId 학생 ID
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    fun deleteUser(studentId: String) {
        accountDeletionService.deleteAccount(studentId)
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
        val updatesCredentialOrIdentity =
            request.password != null || request.bojId != null || request.role != null
        if (!updatesCredentialOrIdentity) {
            return updateUserFields(studentId, request).student
        }

        return credentialSessionCoordinator.execute(studentId) {
            val result = updateUserFields(studentId, request)
            if (result.credentialOrIdentityChanged) {
                refreshTokenService.revokeAllForStudent(studentId)
            }
            result.student
        }
    }

    private fun updateUserFields(studentId: String, request: AdminUserUpdateDto): UserUpdateResult {
        val student = studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=$studentId")
            }

        val before = toAuditSnapshot(student)
        var updatedStudent = student
        var isChanged = false
        var credentialOrIdentityChanged = false
        var credentialVersionIncremented = false

        val newRole = parseRoleOrNull(request.role)
        if (newRole != null && student.role != newRole) {
            updatedStudent = updatedStudent.copy(role = newRole)
            isChanged = true
            credentialOrIdentityChanged = true
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
                if (studentRepository.existsByBojId(bojIdVo)) {
                    throw BusinessException(
                        ErrorCode.DUPLICATE_BOJ_ID,
                        "이미 가입된 백준 아이디입니다. bojId=${bojIdVo.value}"
                    )
                }
                updatedStudent = updatedStudent.copy(bojId = bojIdVo)
                isChanged = true
                credentialOrIdentityChanged = true
            }
        }

        val newPassword = normalizeTextOrNull(request.password)
        if (newPassword != null) {
            val encodedPassword = passwordEncoder.encode(newPassword)
            updatedStudent = updatedStudent.updatePassword(encodedPassword)
            isChanged = true
            credentialOrIdentityChanged = true
            credentialVersionIncremented = true
        }

        if (credentialOrIdentityChanged && !credentialVersionIncremented) {
            updatedStudent = updatedStudent.copy(
                credentialVersion = updatedStudent.credentialVersion + 1
            )
        }

        if (!isChanged) {
            log.info("관리자 사용자 강제 수정: 변경 없음. before={}", before)
            return UserUpdateResult(student, credentialOrIdentityChanged = false)
        }

        val saved = studentRepository.save(updatedStudent)
        val after = toAuditSnapshot(saved)

        log.info("관리자 사용자 강제 수정 완료. before={}, after={}", before, after)
        return UserUpdateResult(saved, credentialOrIdentityChanged)
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
        val value = normalized.removePrefix("ROLE_")

        val parsed = Role.from(value)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 권한입니다. role=$role")

        if (parsed != Role.USER && parsed != Role.ADMIN) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "허용되지 않은 권한입니다. role=$role")
        }

        return parsed
    }

    private fun toAuditSnapshot(student: Student): String {
        val maskedProviderId = SensitiveDataMasker.maskId(student.providerId)
        val maskedBojId = student.bojId?.value?.let { SensitiveDataMasker.maskId(it) }
        return "studentId=${student.id}, provider=${student.provider.value}, providerId=$maskedProviderId, role=${student.role.value}, nickname=${student.nickname.value}, bojId=$maskedBojId"
    }

    /**
     * ISO 8601 형식의 날짜 문자열을 LocalDate로 파싱한다.
     *
     * @param dateString ISO 8601 형식의 날짜 문자열 (예: "2024-01-01")
     * @return LocalDate
     * @throws BusinessException 날짜 형식이 올바르지 않은 경우
     */
    private fun parseDate(dateString: String): LocalDate {
        return try {
            LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 날짜 형식입니다. date=$dateString")
        }
    }

    private data class UserUpdateResult(
        val student: Student,
        val credentialOrIdentityChanged: Boolean
    )
}
