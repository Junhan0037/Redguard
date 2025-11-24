package com.redguard.domain.ratelimit

enum class RateLimitFallbackPolicy {
    ALLOW_ALL,
    BLOCK_ALL,
    STATIC_LIMIT
}
