package com.redguard.api.admin.auth.dto

import com.redguard.domain.admin.AdminRole
import com.redguard.infrastructure.security.TokenPair
import java.time.Instant

data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val roles: Set<AdminRole>
) {
    companion object {
        fun from(tokenPair: TokenPair) = TokenResponse(
                accessToken = tokenPair.accessToken.value,
                accessTokenExpiresAt = tokenPair.accessToken.expiresAt,
                refreshToken = tokenPair.refreshToken.value,
                refreshTokenExpiresAt = tokenPair.refreshToken.expiresAt,
                roles = tokenPair.roles
            )
    }
}
