package com.redguard.application.admin

import com.redguard.domain.admin.AdminStatus
import com.redguard.infrastructure.persistence.admin.AdminUserEntity
import com.redguard.infrastructure.persistence.admin.AdminUserJpaRepository
import com.redguard.infrastructure.security.AdminAuthProperties
import com.redguard.infrastructure.security.AdminPrincipal
import com.redguard.infrastructure.security.AdminTokenStore
import com.redguard.infrastructure.security.JwtTokenProvider
import com.redguard.infrastructure.security.ParsedToken
import com.redguard.infrastructure.security.TokenPair
import io.github.oshai.kotlinlogging.KotlinLogging
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AdminAuthService(
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val adminTokenStore: AdminTokenStore,
    private val adminAuditLogger: AdminAuditLogger,
    private val adminAuthProperties: AdminAuthProperties
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 관리자 로그인
     * - 인증/잠금 해제/로그 기록/토큰 발급까지 수행
     */
    @Transactional
    fun login(loginId: String, password: String, clientIp: String?, userAgent: String?): TokenPair {
        val now = Instant.now()
        val user = adminUserJpaRepository.findByLoginId(loginId) ?: run {
                adminAuditLogger.log(
                    actorId = null,
                    action = AdminAuditAction.AUTH_LOGIN_FAILED,
                    resourceType = "ADMIN_USER",
                    resourceId = loginId,
                    payloadDiff = "존재하지 않는 계정 또는 비밀번호 불일치",
                    ip = clientIp,
                    userAgent = userAgent
                )
                throw RedGuardException(ErrorCode.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.")
            }

        val userId = user.id ?: throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "식별자가 없는 관리자 계정입니다.")
        user.unlockIfExpired(now)
        if (user.status != AdminStatus.ACTIVE) {
            adminUserJpaRepository.save(user)
            throw RedGuardException(ErrorCode.FORBIDDEN, "계정이 잠금 또는 비활성 상태입니다.")
        }

        if (!passwordEncoder.matches(password, user.passwordHash)) {
            user.recordLoginFailure(now, adminAuthProperties.maxFailedAttempts, adminAuthProperties.lockoutDurationMinutes)
            adminUserJpaRepository.save(user)
            adminAuditLogger.log(
                actorId = userId,
                action = AdminAuditAction.AUTH_LOGIN_FAILED,
                resourceType = "ADMIN_USER",
                resourceId = userId.toString(),
                payloadDiff = "로그인 실패 횟수=${user.failedAttempts}",
                ip = clientIp,
                userAgent = userAgent
            )
            throw RedGuardException(ErrorCode.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.")
        }

        user.recordLoginSuccess(now)
        adminUserJpaRepository.save(user)

        val tokenPair = issueTokenPair(user)
        adminTokenStore.storeRefreshToken(userId, tokenPair.refreshToken)
        adminAuditLogger.log(
            actorId = userId,
            action = AdminAuditAction.AUTH_LOGIN,
            resourceType = "ADMIN_USER",
            resourceId = userId.toString(),
            payloadDiff = null,
            ip = clientIp,
            userAgent = userAgent
        )

        return tokenPair
    }

    /**
     * 리프레시 토큰으로 새 토큰 쌍을 재발급
     */
    @Transactional
    fun refresh(refreshToken: String, clientIp: String?, userAgent: String?): TokenPair {
        val parsed = parseRefreshToken(refreshToken)
        val userId = parsed.subject.toLongOrNull() ?: throw RedGuardException(ErrorCode.UNAUTHORIZED, "사용자 정보를 확인할 수 없습니다.")

        if (!adminTokenStore.isRefreshTokenValid(userId, parsed.jti)) {
            throw RedGuardException(ErrorCode.UNAUTHORIZED, "토큰이 만료되었거나 회수되었습니다.")
        }

        val user = adminUserJpaRepository.findById(userId).orElseThrow { RedGuardException(ErrorCode.UNAUTHORIZED, "사용자 정보를 확인할 수 없습니다.") }
        val now = Instant.now()
        user.unlockIfExpired(now)
        adminUserJpaRepository.save(user)
        if (user.status != AdminStatus.ACTIVE) {
            throw RedGuardException(ErrorCode.FORBIDDEN, "계정이 잠금 또는 비활성 상태입니다.")
        }

        val tokenPair = issueTokenPair(user)
        adminTokenStore.revokeRefreshToken(userId, parsed.jti)
        adminTokenStore.storeRefreshToken(userId, tokenPair.refreshToken)
        adminAuditLogger.log(
            actorId = userId,
            action = AdminAuditAction.AUTH_REFRESH,
            resourceType = "ADMIN_USER",
            resourceId = userId.toString(),
            payloadDiff = null,
            ip = clientIp,
            userAgent = userAgent
        )

        return tokenPair
    }

    /**
     * 전달받은 토큰을 블랙리스트/회수 처리하고 감사 로그를 남김
     */
    @Transactional
    fun logout(
        accessToken: String?,
        refreshToken: String?,
        principal: AdminPrincipal,
        clientIp: String?,
        userAgent: String?
    ) {
        accessToken?.let {
            runCatching {
                val parsed = jwtTokenProvider.parseAccessToken(it)
                adminTokenStore.blacklistAccessToken(parsed.jti, parsed.expiresAt)
            }.onFailure { ex -> logger.warn(ex) { "액세스 토큰 블랙리스트 등록 실패" } }
        }

        refreshToken?.let {
            runCatching {
                val parsed = parseRefreshToken(it)
                adminTokenStore.revokeRefreshToken(principal.id, parsed.jti)
            }.onFailure { ex -> logger.warn(ex) { "리프레시 토큰 회수 실패" } }
        }

        adminAuditLogger.log(
            actorId = principal.id,
            action = AdminAuditAction.AUTH_LOGOUT,
            resourceType = "ADMIN_USER",
            resourceId = principal.id.toString(),
            payloadDiff = null,
            ip = clientIp,
            userAgent = userAgent
        )
    }

    /**
     * 사용자 정보 기반 JWT 페어 발급
     */
    private fun issueTokenPair(user: AdminUserEntity): TokenPair {
        val userId = user.id ?: throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "식별자가 없는 관리자 계정입니다.")
        val roles = user.roles.toSet()
        val access = jwtTokenProvider.generateAccessToken(userId.toString(), roles)
        val refresh = jwtTokenProvider.generateRefreshToken(userId.toString())
        return TokenPair(
            accessToken = access,
            refreshToken = refresh,
            roles = roles
        )
    }

    /**
     * 리프레시 토큰 파싱 시 실패를 401로 매핑
     */
    private fun parseRefreshToken(refreshToken: String): ParsedToken =
        runCatching { jwtTokenProvider.parseRefreshToken(refreshToken) }
            .getOrElse {
                throw RedGuardException(ErrorCode.UNAUTHORIZED, "토큰이 만료되었거나 손상되었습니다.")
            }
}
