package com.redguard.infrastructure.security

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class AdminTokenStore(
    private val redisTemplate: StringRedisTemplate,
    private val properties: AdminAuthProperties
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 리프레시 토큰을 사용자별 JTI 키로 저장하며 TTL을 설정
     */
    fun storeRefreshToken(userId: Long, token: GeneratedToken) {
        val ttl = Duration.between(Instant.now(), token.expiresAt).coerceAtLeast(Duration.ofSeconds(1))
        val key = refreshKey(userId, token.jti)
        redisTemplate.opsForValue().set(key, "1", ttl)
    }

    /**
     * 저장된 리프레시 토큰(JTI)이 유효한지 확인
     */
    fun isRefreshTokenValid(userId: Long, jti: String): Boolean = redisTemplate.hasKey(refreshKey(userId, jti))

    /**
     * 특정 리프레시 토큰을 즉시 폐기
     */
    fun revokeRefreshToken(userId: Long, jti: String): Boolean = redisTemplate.delete(refreshKey(userId, jti))

    /**
     * 액세스 토큰 JTI를 블랙리스트에 등록해 남은 만료 시간만큼 유지
     */
    fun blacklistAccessToken(jti: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(), expiresAt)
        if (ttl.isNegative || ttl.isZero) {
            return
        }
        redisTemplate.opsForValue().set(blacklistKey(jti), "1", ttl)
    }

    /**
     * 블랙리스트 여부 확인
     */
    fun isBlacklisted(jti: String): Boolean = redisTemplate.hasKey(blacklistKey(jti))

    private fun refreshKey(userId: Long, jti: String): String = "${properties.tokenNamespace}:refresh:$userId:$jti"

    private fun blacklistKey(jti: String): String = "${properties.tokenNamespace}:blacklist:$jti"
}
