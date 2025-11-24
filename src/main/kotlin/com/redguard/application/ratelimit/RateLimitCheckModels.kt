package com.redguard.application.ratelimit

import com.redguard.domain.ratelimit.RateLimitScope
import java.time.Instant
import com.redguard.domain.policy.ApiHttpMethod

/**
 * Rate Limit 평가에 사용되는 정책 스냅샷
 * Plan/ApiPolicy 조합 등 상위 레이어에서 결정한 한도 값을 전달
 */
data class RateLimitPolicySnapshot(
    val limitPerSecond: Long? = null,
    val limitPerMinute: Long? = null,
    val limitPerDay: Long? = null,
    val quotaPerDay: Long? = null,
    val quotaPerMonth: Long? = null
)

/**
 * Rate Limit 체크 입력 커맨드
 */
data class RateLimitCheckCommand(
    val scope: RateLimitScope,
    val tenantId: String,
    val userId: String? = null,
    val apiPath: String? = null,
    val httpMethod: ApiHttpMethod,
    val timestamp: Instant,
    val increment: Long = 1,
    val policy: RateLimitPolicySnapshot
)

/**
 * Rate Limit 체크 결과 및 세부 사용량
 */
data class RateLimitCheckResult(
    val decision: RateLimitDecision,
    val allowed: Boolean,
    val windowUsages: WindowUsages,
    val quotaUsages: QuotaUsages,
    val fallbackApplied: Boolean
)

enum class RateLimitDecision {
    ALLOWED,
    RATE_LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    FALLBACK_ALLOW,
    FALLBACK_BLOCK
}

data class WindowUsages(
    val second: WindowUsage? = null,
    val minute: WindowUsage? = null,
    val day: WindowUsage? = null
)

data class WindowUsage(
    val allowed: Boolean,
    val limit: Long?,
    val effectiveCount: Long,
    val currentBucketCount: Long,
    val previousBucketCount: Long
)

data class QuotaUsages(
    val daily: QuotaUsage? = null,
    val monthly: QuotaUsage? = null
)

data class QuotaUsage(
    val allowed: Boolean,
    val limit: Long?,
    val totalCount: Long
)
