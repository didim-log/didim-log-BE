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
        return method == "GET" && (path.startsWith("/api/v1/notices") || path.startsWith("/api/v1/system/status"))
    }

    private fun isAdminUser(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated) {
            return false
        }
        return authentication.authorities.any { it.authority == "ROLE_ADMIN" }
    }
}

