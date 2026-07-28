package com.didimlog.global.util

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper

/**
 * HTTP 요청 관련 유틸리티
 */
object HttpRequestUtil {

    /**
     * 클라이언트 IP 주소를 추출합니다.
     * 프록시나 로드밸런서를 통한 요청도 고려합니다.
     *
     * @param request HTTP 요청
     * @return 클라이언트 IP 주소
     */
    fun getClientIpAddress(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (xForwardedFor != null && xForwardedFor.isNotBlank()) {
            return xForwardedFor.split(",").first().trim()
        }

        val xRealIp = request.getHeader("X-Real-IP")
        if (xRealIp != null && xRealIp.isNotBlank()) {
            return xRealIp
        }

        return request.remoteAddr ?: "unknown"
    }

    /**
     * 전달 헤더를 적용한 래퍼를 벗겨 실제 연결 주소를 반환한다.
     * 신뢰 프록시 설정이 없으면 forwarded header를 사용하지 않으므로 프록시 뒤의 요청은 raw peer 주소 기준으로 묶인다.
     */
    fun getConnectionRemoteAddress(request: HttpServletRequest): String {
        var current: HttpServletRequest = request
        while (current is HttpServletRequestWrapper) {
            val wrapped = current.request
            if (wrapped !is HttpServletRequest || wrapped === current) {
                break
            }
            current = wrapped
        }
        return current.remoteAddr ?: "unknown"
    }
}











