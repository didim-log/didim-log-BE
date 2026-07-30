package com.didimlog.global.util

import jakarta.servlet.http.HttpServletRequest

/**
 * HTTP 요청 관련 유틸리티
 */
object HttpRequestUtil {

    /**
     * 서버가 검증한 클라이언트 주소를 반환합니다.
     * Tomcat RemoteIpValve가 신뢰하는 내부 프록시의 전달 헤더만 remoteAddr에 반영합니다.
     *
     * @param request HTTP 요청
     * @return 클라이언트 IP 주소
     */
    fun getClientIpAddress(request: HttpServletRequest): String {
        return request.remoteAddr ?: "unknown"
    }
}










