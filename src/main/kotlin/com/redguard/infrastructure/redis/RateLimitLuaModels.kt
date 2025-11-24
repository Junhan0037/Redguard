package com.redguard.infrastructure.redis

import com.redguard.domain.ratelimit.RedisKeyDimensions
import java.time.Instant
import java.util.Locale

/**
 * Lua 스크립트 실행 입력 모델
 */
data class RateLimitScriptRequest(
    val dimensions: RedisKeyDimensions,
    val timestamp: Instant,
    val limitPerSecond: Long? = null,
    val limitPerMinute: Long? = null,
    val limitPerDay: Long? = null,
    val quotaPerDay: Long? = null,
    val quotaPerMonth: Long? = null,
    val increment: Long = 1
)

/**
 * 슬라이딩 윈도우 단위 결과
 */
data class WindowResult(
    val allowed: Boolean,
    val effectiveCount: Long,
    val currentBucketCount: Long,
    val previousBucketCount: Long
)

/**
 * 쿼터(일/월) 결과
 */
data class QuotaResult(
    val allowed: Boolean,
    val totalCount: Long
)

/**
 * Lua 스크립트 전체 실행 결과
 */
data class RateLimitScriptResult(
    val second: WindowResult?,
    val minute: WindowResult?,
    val day: WindowResult?,
    val dailyQuota: QuotaResult?,
    val monthlyQuota: QuotaResult?
)

/**
 * Lua 스크립트에 전달되는 KEYS/ARGV 페이로드
 */
data class RateLimitScriptPayload(
    val keys: List<String>,
    val args: List<String>,
    val windowPayloads: WindowPayloads
)

/**
 * 윈도우별 키/TTL/가중치 정보를 담는 모델
 */
data class WindowPayload(
    val currentKey: String,
    val previousKey: String,
    val limit: Long,
    val ttlSeconds: Long,
    val previousWeight: Double
) {
    fun previousWeightAsString(): String = String.format(Locale.US, "%.6f", previousWeight)
}

/**
 * 초/분/일 윈도우 페이로드 모음
 */
data class WindowPayloads(
    val second: WindowPayload,
    val minute: WindowPayload,
    val day: WindowPayload
)
