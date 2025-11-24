package com.redguard.infrastructure.config

import com.redguard.domain.ratelimit.RateLimitFallbackPolicy
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "redguard.rate-limit")
class RateLimitProperties {
    var fallbackPolicy: RateLimitFallbackPolicy = RateLimitFallbackPolicy.ALLOW_ALL
    var staticLimitPerSecond: Long? = null
    var staticLimitPerMinute: Long? = null
    var staticLimitPerDay: Long? = null
    var staticQuotaPerDay: Long? = null
    var staticQuotaPerMonth: Long? = null
}
