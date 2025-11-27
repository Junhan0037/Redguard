package com.redguard.infrastructure.security

import com.redguard.domain.admin.AdminRole

data class TokenPair(
    val accessToken: GeneratedToken,
    val refreshToken: GeneratedToken,
    val roles: Set<AdminRole>
)
