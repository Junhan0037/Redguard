package com.redguard.domain.ratelimit

/**
 * Redis 키를 구성하는 도메인 표현
 * 스코프에 따라 userId/apiPath는 필수 또는 옵션으로 사용
 */
data class RedisKeyDimensions(
    val scope: RateLimitScope,
    val tenantId: String,
    val userId: String? = null,
    val apiPath: String? = null
)
