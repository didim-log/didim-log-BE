package com.didimlog.global.auth

import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.StudentRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
    private val jwtTokenProviderProvider: ObjectProvider<JwtTokenProvider>,
    private val studentRepositoryProvider: ObjectProvider<StudentRepository>
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private val ACCESS_TOKEN_ROLES = setOf(Role.USER.value, Role.ADMIN.value)
        
        /**
         * JWT 필터를 적용하지 않을 경로 목록
         * Swagger UI 경로는 HTTP Basic Authentication으로 처리되므로 제외
         */
        private val EXCLUDE_PATHS = listOf(
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-resources"
        )
    }

    /**
     * Swagger UI 경로는 JWT 필터를 건너뛰어 HTTP Basic Authentication이 처리하도록 함
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return EXCLUDE_PATHS.any { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val jwtTokenProvider = jwtTokenProviderProvider.getIfAvailable()
        val studentRepository = studentRepositoryProvider.getIfAvailable()
        if (jwtTokenProvider == null || studentRepository == null) {
            filterChain.doFilter(request, response)
            return
        }

        val token = extractToken(request)

        val tokenIdentity = token?.let(jwtTokenProvider::getAccessTokenIdentity)
        if (tokenIdentity != null) {
            try {
                if (tokenIdentity.role !in ACCESS_TOKEN_ROLES) {
                    SecurityContextHolder.clearContext()
                    filterChain.doFilter(request, response)
                    return
                }

                val student = studentRepository.findById(tokenIdentity.studentId).orElse(null)
                val currentBojId = student?.bojId?.value
                if (
                    student == null ||
                    currentBojId != tokenIdentity.bojId ||
                    student.credentialVersion != tokenIdentity.credentialVersion ||
                    student.role.value != tokenIdentity.role ||
                    student.role.value !in ACCESS_TOKEN_ROLES
                ) {
                    SecurityContextHolder.clearContext()
                    filterChain.doFilter(request, response)
                    return
                }

                val authorities = listOf(SimpleGrantedAuthority("ROLE_${tokenIdentity.role}"))
                val authentication = UsernamePasswordAuthenticationToken(
                    tokenIdentity.studentId,
                    null,
                    authorities
                )
                
                // SecurityContextHolder에 Authentication 설정
                SecurityContextHolder.getContext().authentication = authentication
                
                log.debug(
                    "JWT 인증 성공: studentId={}, role={}",
                    tokenIdentity.studentId,
                    tokenIdentity.role
                )
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
