package com.didimlog.global.util

import jakarta.servlet.http.HttpServletRequest

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
}


