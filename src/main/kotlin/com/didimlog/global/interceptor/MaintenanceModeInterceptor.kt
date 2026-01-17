package com.didimlog.global.interceptor

import com.didimlog.global.system.MaintenanceModeService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 유지보수 모드 인터셉터
 * 유지보수 모드가 활성화되어 있을 때, ADMIN 권한이 없는 요청을 차단한다.
 * 
 * 허용되는 요청:
 * - 공개 API (GET /api/v1/notices, GET /api/v1/system/status)
 * - 로그인 API (POST /api/v1/auth/login, POST /api/v1/auth/super-admin)
 * - ADMIN 권한을 가진 사용자의 모든 요청
 * 
 * 주의: 이 인터셉터는 DispatcherServlet 이후에 실행되므로,
 * JwtAuthenticationFilter가 먼저 실행되어 SecurityContext에 인증 정보가 설정된 후에 실행됩니다.
 */
@Component
class MaintenanceModeInterceptor(
    private val maintenanceModeServiceProvider: ObjectProvider<MaintenanceModeService>
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val maintenanceModeService = maintenanceModeServiceProvider.getIfAvailable() ?: return true
        if (!maintenanceModeService.isMaintenanceMode()) {
            return true
        }

        if (shouldAllowRequest(request)) {
            return true
        }

        throw BusinessException(ErrorCode.MAINTENANCE_MODE, ErrorCode.MAINTENANCE_MODE.message)
    }

    private fun shouldAllowRequest(request: HttpServletRequest): Boolean {
        if (isPublicApi(request)) {
            return true
        }
        if (isAdminUser()) {
            return true
        }
        return false
    }

    private fun isPublicApi(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        val method = request.method
        
        if (method == "GET" && isPublicGetPath(path)) {
            return true
        }
        
        if (method == "POST" && isPublicPostPath(path)) {
            return true
        }
        
        return false
    }
    
    private fun isPublicGetPath(path: String): Boolean {
        return path.startsWith("/api/v1/notices") || path.startsWith("/api/v1/system/status")
    }
    
    private fun isPublicPostPath(path: String): Boolean {
        return path == "/api/v1/auth/login" || path == "/api/v1/auth/super-admin"
    }

    private fun isAdminUser(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated) {
            return false
        }
        return authentication.authorities.any { it.authority == "ROLE_ADMIN" }
    }
}

