package com.didimlog.global.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증 필터
 * 요청 헤더의 Authorization Bearer 토큰을 검증하고, 유효한 경우 SecurityContextHolder에 Authentication 객체를 설정한다.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = extractToken(request)

        if (token != null && jwtTokenProvider.validateToken(token)) {
            try {
                val userId = jwtTokenProvider.getSubject(token)
                val role = jwtTokenProvider.getRole(token) ?: "USER" // 기본값: USER
                
                // Authentication 객체 생성 (토큰의 role 정보를 기반으로 권한 설정)
                val authorities = listOf(SimpleGrantedAuthority("ROLE_$role"))
                val authentication = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    authorities
                )
                
                // SecurityContextHolder에 Authentication 설정
                SecurityContextHolder.getContext().authentication = authentication
                
                log.debug("JWT 인증 성공: userId=$userId, role=$role")
            } catch (e: Exception) {
                log.error("JWT 토큰 처리 중 오류 발생", e)
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 요청 헤더에서 JWT 토큰을 추출한다.
     *
     * @param request HTTP 요청
     * @return 추출된 토큰 (없으면 null)
     */
    private fun extractToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null

        if (bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length)
        }

        return null
    }
}

