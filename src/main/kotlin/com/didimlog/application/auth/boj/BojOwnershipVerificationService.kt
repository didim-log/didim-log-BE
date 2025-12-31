package com.didimlog.application.auth.boj

import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

@Service
class BojOwnershipVerificationService(
    private val codeStore: BojVerificationCodeStore,
    private val studentRepository: StudentRepository
) {

    private val log = LoggerFactory.getLogger(BojOwnershipVerificationService::class.java)

    companion object {
        private const val CODE_PREFIX = "DIDIM-LOG-"
        private const val CODE_LENGTH = 6
        private const val DEFAULT_TTL_SECONDS = 5 * 60L
        private const val RATE_LIMIT_KEY_PREFIX = "boj:code:rate:"
        private const val MAX_REQUESTS_PER_MINUTE = 5
        private const val RATE_LIMIT_TTL_SECONDS = 60L
    }

    data class IssuedCode(
        val sessionId: String,
        val code: String,
        val expiresInSeconds: Long
    )

    @Transactional(readOnly = true)
    fun issueVerificationCode(identifier: String?): IssuedCode {
        // Rate Limiting 체크
        val rateLimitKey = RATE_LIMIT_KEY_PREFIX + (identifier ?: "unknown")
        val currentCount = codeStore.getRateLimitCount(rateLimitKey)

        if (currentCount >= MAX_REQUESTS_PER_MINUTE) {
            throw BusinessException(
                ErrorCode.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. 1분 후 다시 시도해주세요."
            )
        }

        codeStore.incrementRateLimitCount(rateLimitKey, RATE_LIMIT_TTL_SECONDS)

        val sessionId = UUID.randomUUID().toString()
        val code = CODE_PREFIX + randomUpperAlphaNumeric(CODE_LENGTH)
        codeStore.save(sessionId, code, DEFAULT_TTL_SECONDS)
        return IssuedCode(sessionId = sessionId, code = code, expiresInSeconds = DEFAULT_TTL_SECONDS)
    }

    @Transactional
    fun verifyOwnership(sessionId: String, bojId: String) {
        val storedCode = codeStore.find(sessionId)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "인증 코드가 만료되었거나 존재하지 않습니다.")

        val bojIdVo = BojId(bojId)
        val profileBodyText = fetchBojProfileBodyText(bojIdVo.value)

        if (!isCodePresentInMessage(profileBodyText, storedCode)) {
            throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "프로필 페이지에서 코드를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드($storedCode)를 정확히 입력하고 저장한 후, 몇 초 대기한 뒤 다시 시도해주세요."
            )
        }

        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow { BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. bojId=$bojId") }

        val verifiedStudent = student.copy(isVerified = true, role = Role.USER, bojId = student.bojId ?: bojIdVo)
        studentRepository.save(verifiedStudent)

        codeStore.delete(sessionId)
    }

    /**
     * Jsoup을 사용하여 백준 프로필 페이지를 직접 크롤링하고 본문(Body) 전체 텍스트를 가져온다.
     *
     * @param bojId BOJ ID
     * @return 프로필 페이지 본문 전체 텍스트
     * @throws BusinessException 크롤링 실패 시 (네트워크 오류, 404 등)
     */
    private fun fetchBojProfileBodyText(bojId: String): String {
        val url = "https://www.acmicpc.net/user/$bojId"
        return try {
            val document = connectToBojProfile(url)
            document.body().text()
        } catch (e: org.jsoup.HttpStatusException) {
            handleHttpStatusException(e, bojId)
        } catch (e: java.net.SocketTimeoutException) {
            handleTimeoutException(e, bojId)
        } catch (e: java.net.UnknownHostException) {
            handleNetworkException(e, bojId)
        } catch (e: Exception) {
            handleGenericException(e, bojId)
        }
    }

    private fun connectToBojProfile(url: String): org.jsoup.nodes.Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(10000)
            .get()
    }

    private fun handleHttpStatusException(e: org.jsoup.HttpStatusException, bojId: String): Nothing {
        if (e.statusCode == 404) {
            throwNotFoundException(bojId)
        }
        if (e.statusCode == 403) {
            throwAccessDeniedException(bojId)
        }
        throwInternalErrorException(bojId, e.statusCode, e.message)
    }

    private fun throwNotFoundException(bojId: String): Nothing {
        log.warn("BOJ 프로필을 찾을 수 없음: bojId=$bojId, status=404")
        throw BusinessException(
            ErrorCode.COMMON_RESOURCE_NOT_FOUND,
            "백준 프로필을 찾을 수 없습니다. BOJ ID가 올바른지 확인해주세요. bojId=$bojId"
        )
    }

    private fun throwAccessDeniedException(bojId: String): Nothing {
        log.warn("BOJ 프로필 접근 거부: bojId=$bojId, status=403")
        throw BusinessException(
            ErrorCode.COMMON_INVALID_INPUT,
            "백준 프로필 페이지에 접근할 수 없습니다. 프로필이 공개되어 있는지 확인해주세요. bojId=$bojId"
        )
    }

    private fun throwInternalErrorException(bojId: String, statusCode: Int, message: String?): Nothing {
        log.error("BOJ 프로필 크롤링 실패: bojId=$bojId, status=$statusCode, message=$message")
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "백준 프로필 페이지를 가져오는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요. bojId=$bojId"
        )
    }

    private fun handleTimeoutException(e: java.net.SocketTimeoutException, bojId: String): Nothing {
        log.error("BOJ 프로필 크롤링 타임아웃: bojId=$bojId, message=${e.message}", e)
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "백준 프로필 페이지를 가져오는 중 시간이 초과되었습니다. 잠시 후 다시 시도해주세요. bojId=$bojId"
        )
    }

    private fun handleNetworkException(e: java.net.UnknownHostException, bojId: String): Nothing {
        log.error("BOJ 프로필 크롤링 네트워크 오류: bojId=$bojId, message=${e.message}", e)
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요. bojId=$bojId"
        )
    }

    private fun handleGenericException(e: Exception, bojId: String): Nothing {
        log.error("BOJ 프로필 크롤링 실패: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "백준 프로필 페이지를 가져오는 중 오류가 발생했습니다. bojId=$bojId, error=${e.message}"
        )
    }

    /**
     * 상태 메시지에 코드가 정확히 포함되어 있는지 검증한다.
     * 부분 문자열 일치 문제를 방지하기 위해 단어 경계 또는 공백/문자열 끝을 확인한다.
     *
     * 예시:
     * - 코드 "DIDIM-LOG-1234"가 상태 메시지 "DIDIM-LOG-12345"에 포함되는 것을 방지
     * - 코드 "DIDIM-LOG-1234"가 상태 메시지 "DIDIM-LOG-1234"와 정확히 일치하면 통과
     * - 코드 "DIDIM-LOG-1234"가 상태 메시지 "코드: DIDIM-LOG-1234 입니다"에 포함되면 통과
     *
     * @param statusMessage BOJ 프로필 상태 메시지
     * @param code 검증할 코드
     * @return 코드가 정확히 포함되어 있으면 true
     */
    private fun isCodePresentInMessage(statusMessage: String, code: String): Boolean {
        val trimmedStatus = statusMessage.trim()
        val trimmedCode = code.trim()

        // 정확한 일치
        if (trimmedStatus == trimmedCode) {
            return true
        }

        // 단어 경계 또는 공백/문자열 끝으로 감싸져 있는지 확인 (부분 문자열 일치 방지)
        // Regex.escape를 사용하여 특수문자(하이픈 등)를 이스케이프 처리
        // \b는 단어 경계를 의미하지만, 하이픈이 포함된 경우 더 정확한 패턴 사용
        val escapedCode = Regex.escape(trimmedCode)
        
        // 패턴: 문자열 시작 또는 공백/비단어 문자 뒤에 코드가 오고, 문자열 끝 또는 공백/비단어 문자가 뒤에 오는 경우
        val pattern = Regex("(^|[\\s\\W])$escapedCode([\\s\\W]|\$)")
        return pattern.containsMatchIn(trimmedStatus)
    }

    private fun randomUpperAlphaNumeric(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(chars[random.nextInt(chars.length)])
        }
        return builder.toString()
    }
}

