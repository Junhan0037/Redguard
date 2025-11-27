package com.redguard.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "redguard.admin-auth")
data class AdminAuthProperties(
    // JWT iss 클레임에 넣을 발급자 식별자
    val issuer: String,
    // Access Token 서명용 비밀키
    val accessTokenSecret: String,
    // Refresh Token 서명용 비밀키
    val refreshTokenSecret: String,
    // Access Token TTL
    val accessTokenTtl: Duration,
    // Refresh Token TTL
    val refreshTokenTtl: Duration,
    // 허용 가능한 클록 스큐(초)
    val allowedClockSkewSeconds: Long = 30,
    // 로그인 실패 허용 횟수(연속)
    val maxFailedAttempts: Int = 5,
    // 잠금 유지 시간(분)
    val lockoutDurationMinutes: Long = 15,
    // Redis 키 네임스페이스
    val tokenNamespace: String = "admin:auth"
)
