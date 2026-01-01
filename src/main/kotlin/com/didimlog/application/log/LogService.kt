package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
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
     * @param bojId BOJ ID (선택, null 가능)
     * @return 생성된 Log 엔티티
     */
    @Transactional
    fun createLog(title: String, content: String, code: String, bojId: String? = null): Log {
        // LogContent는 notBlank를 요구하므로, 빈 문자열인 경우 기본값 제공
        val logContent = if (content.isBlank()) " " else content
        val bojIdVo = bojId?.let { BojId(it) }
        val log = Log(
            title = LogTitle(title),
            content = LogContent(logContent),
            code = LogCode(code),
            bojId = bojIdVo
        )
        return logRepository.save(log)
    }
}

