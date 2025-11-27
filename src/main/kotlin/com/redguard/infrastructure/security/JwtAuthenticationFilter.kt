package com.redguard.infrastructure.security

import com.redguard.domain.admin.AdminStatus
import com.redguard.infrastructure.persistence.admin.AdminUserJpaRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val adminTokenStore: AdminTokenStore,
    private val authenticationEntryPoint: AuthenticationEntryPoint
) : OncePerRequestFilter() {
    private val log = KotlinLogging.logger {}

    /**
     * 인증 엔드포인트(/admin/auth/..)는 필터를 거치지 않음
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        return path.startsWith("/admin/auth/")
    }

    /**
     * JWT 파싱, 블랙리스트 확인, 계정 잠금 해제/검증 후 SecurityContext 설정
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = resolveToken(request)
        if (token.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }
        try {
            val parsed = jwtTokenProvider.parseAccessToken(token)
            if (adminTokenStore.isBlacklisted(parsed.jti)) {
                throw InsufficientAuthenticationException("블랙리스트 처리된 토큰입니다.")
            }

            val userId = parsed.subject.toLongOrNull() ?: throw InsufficientAuthenticationException("사용자 정보를 확인할 수 없습니다.")
            val user = adminUserJpaRepository.findByIdOrNull(userId) ?: throw InsufficientAuthenticationException("사용자 정보를 확인할 수 없습니다.")

            val now = Instant.now()
            user.unlockIfExpired(now)
            if (user.status != AdminStatus.ACTIVE) {
                adminUserJpaRepository.save(user)
                throw InsufficientAuthenticationException("계정이 잠금 또는 비활성 상태입니다.")
            }
            adminUserJpaRepository.save(user)

            val persistedId = user.id ?: throw InsufficientAuthenticationException("사용자 정보를 확인할 수 없습니다.")
            val principal = AdminPrincipal(persistedId, user.loginId, user.roles.toSet())
            val authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities).also {
                it.details = WebAuthenticationDetailsSource().buildDetails(request)
            }
            SecurityContextHolder.getContext().authentication = authentication
        } catch (ex: Exception) {
            SecurityContextHolder.clearContext()
            log.warn(ex) { "관리자 인증 실패" }
            authenticationEntryPoint.commence(
                request,
                response,
                InsufficientAuthenticationException(ex.message ?: "인증에 실패했습니다.", ex)
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) {
            return null
        }
        return header.substringAfter("Bearer ").trim().ifEmpty { null }
    }
}
