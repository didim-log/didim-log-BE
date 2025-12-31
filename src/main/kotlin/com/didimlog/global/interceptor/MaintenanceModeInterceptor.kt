package com.didimlog.global.interceptor

import com.didimlog.global.system.MaintenanceModeService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.authority.SimpleGrantedAuthority
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

        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated) {
            throw BusinessException(ErrorCode.MAINTENANCE_MODE, ErrorCode.MAINTENANCE_MODE.message)
        }

        val isAdmin = authentication.authorities.contains(SimpleGrantedAuthority("ROLE_ADMIN"))
        if (!isAdmin) {
            throw BusinessException(ErrorCode.MAINTENANCE_MODE, ErrorCode.MAINTENANCE_MODE.message)
        }

        return true
    }
}

