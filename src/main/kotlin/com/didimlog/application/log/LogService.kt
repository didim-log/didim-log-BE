package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 로그 생성 서비스
 */
@Service
class LogService(
    private val logRepository: LogRepository
) {

    /**
     * 새로운 로그를 생성합니다.
     *
     * @param title 로그 제목
     * @param content 로그 내용 (빈 문자열 허용)
     * @param code 사용자 코드
     * @param studentId 변경되지 않는 로그 소유자 ID
     * @param bojId 로그 생성 시점의 BOJ ID
     * @param isSuccess 풀이 성공 여부 (선택, null 가능)
     * @return 생성된 Log 엔티티
     */
    @Transactional
    fun createLog(
        title: String,
        content: String,
        code: String,
        studentId: String,
        bojId: String? = null,
        isSuccess: Boolean? = null
    ): Log {
        require(studentId.isNotBlank()) { "학생 ID는 필수입니다." }
        // LogContent는 notBlank를 요구하므로, 빈 문자열인 경우 플레이스홀더를 사용한다.
        val logContent = when {
            content.isBlank() -> "(empty)"
            else -> content
        }
        val bojIdVo = bojId?.let { BojId(it) }
        val log = Log(
            title = LogTitle(title),
            content = LogContent(logContent),
            code = LogCode(code),
            studentId = studentId,
            bojId = bojIdVo,
            isSuccess = isSuccess
        )
        return logRepository.save(log)
    }

    /**
     * AI 리뷰 피드백을 업데이트합니다.
     *
     * @param logId 로그 ID
     * @param requesterStudentId 요청자 학생 ID
     * @param status 피드백 상태 (LIKE/DISLIKE)
     * @param reason 부정적 피드백의 이유 (선택)
     * @return 업데이트된 Log 엔티티
     * @throws BusinessException 인증 정보가 없거나 로그를 찾을 수 없거나 소유자가 아닌 경우
     */
    @Transactional
    fun updateFeedback(
        logId: String,
        requesterStudentId: String,
        status: AiFeedbackStatus,
        reason: String? = null
    ): Log {
        if (requesterStudentId.isBlank()) {
            throw BusinessException(ErrorCode.UNAUTHORIZED, "인증이 필요합니다.")
        }

        val log = logRepository.findById(logId)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "로그를 찾을 수 없습니다. logId=$logId")
            }

        if (log.studentId != requesterStudentId) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, "본인 로그에 대해서만 피드백을 제출할 수 있습니다.")
        }

        val updatedLog = log.updateFeedback(status, reason)
        return logRepository.save(updatedLog)
    }

    /**
     * 로그 템플릿(로그 본문)을 조회합니다.
     *
     * @param logId 로그 ID
     * @param requesterStudentId 요청자 학생 ID
     * @return 로그 본문 문자열
     */
    @Transactional(readOnly = true)
    fun getLogTemplate(logId: String, requesterStudentId: String): String {
        if (requesterStudentId.isBlank()) {
            throw BusinessException(ErrorCode.UNAUTHORIZED, "인증이 필요합니다.")
        }

        val log = logRepository.findById(logId)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "로그를 찾을 수 없습니다. logId=$logId")
            }

        if (log.studentId != requesterStudentId) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, "본인 로그에 대해서만 템플릿을 조회할 수 있습니다.")
        }

        return log.content.value
    }
}
