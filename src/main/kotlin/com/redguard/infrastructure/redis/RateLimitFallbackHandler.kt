package com.redguard.infrastructure.redis

import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitFallbackPolicy
import com.redguard.domain.ratelimit.RateLimitWindow
import com.redguard.infrastructure.config.RateLimitProperties
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * Redis 실패 시 폴백 정책을 적용해 결과를 생성하는 핸들러
 */
@Component
class RateLimitFallbackHandler(
    private val rateLimitProperties: RateLimitProperties,
    private val redisKeyBuilder: RedisKeyBuilder
) {

    private val fallbackBuckets = ConcurrentHashMap<String, FallbackBucket>()

    /**
     * 설정된 폴백 정책에 따라 ALLOW/BLOCK/STATIC 결과를 반환
     */
    fun handle(request: RateLimitScriptRequest): RateLimitScriptResult {
        return when (rateLimitProperties.fallbackPolicy) {
            RateLimitFallbackPolicy.ALLOW_ALL -> fallbackAllowAll(request)
            RateLimitFallbackPolicy.BLOCK_ALL -> fallbackBlockAll(request)
            RateLimitFallbackPolicy.STATIC_LIMIT -> fallbackStaticLimit(request)
        }
    }

    /**
     * 현재 적용 중인 폴백 정책을 노출
     */
    fun policy(): RateLimitFallbackPolicy = rateLimitProperties.fallbackPolicy

    /**
     * 장애 시 모든 요청을 허용하며 카운트를 0으로 반환
     */
    private fun fallbackAllowAll(request: RateLimitScriptRequest): RateLimitScriptResult {
        return RateLimitScriptResult(
            second = fallbackFixedWindowResult(allowed = true, limit = request.limitPerSecond),
            minute = fallbackFixedWindowResult(allowed = true, limit = request.limitPerMinute),
            day = fallbackFixedWindowResult(allowed = true, limit = request.limitPerDay),
            dailyQuota = fallbackFixedQuotaResult(allowed = true, limit = request.quotaPerDay),
            monthlyQuota = fallbackFixedQuotaResult(allowed = true, limit = request.quotaPerMonth)
        )
    }

    /**
     * 장애 시 모든 요청을 차단하며 limit 값으로 즉시 초과로 처리
     */
    private fun fallbackBlockAll(request: RateLimitScriptRequest): RateLimitScriptResult {
        return RateLimitScriptResult(
            second = fallbackFixedWindowResult(allowed = false, limit = request.limitPerSecond),
            minute = fallbackFixedWindowResult(allowed = false, limit = request.limitPerMinute),
            day = fallbackFixedWindowResult(allowed = false, limit = request.limitPerDay),
            dailyQuota = fallbackFixedQuotaResult(allowed = false, limit = request.quotaPerDay),
            monthlyQuota = fallbackFixedQuotaResult(allowed = false, limit = request.quotaPerMonth)
        )
    }

    /**
     * 인메모리 버킷으로 정적 한도를 적용해 제한을 유지
     */
    private fun fallbackStaticLimit(request: RateLimitScriptRequest): RateLimitScriptResult {
        val timestamp = request.timestamp
        cleanupExpiredFallbackBuckets(timestamp)
        return RateLimitScriptResult(
            second = fallbackStaticWindow(
                window = RateLimitWindow.SECOND,
                limit = preferredLimit(rateLimitProperties.staticLimitPerSecond, request.limitPerSecond),
                request = request,
                timestamp = timestamp
            ),
            minute = fallbackStaticWindow(
                window = RateLimitWindow.MINUTE,
                limit = preferredLimit(rateLimitProperties.staticLimitPerMinute, request.limitPerMinute),
                request = request,
                timestamp = timestamp
            ),
            day = fallbackStaticWindow(
                window = RateLimitWindow.DAY,
                limit = preferredLimit(rateLimitProperties.staticLimitPerDay, request.limitPerDay),
                request = request,
                timestamp = timestamp
            ),
            dailyQuota = fallbackStaticQuota(
                period = QuotaPeriod.DAILY,
                limit = preferredLimit(rateLimitProperties.staticQuotaPerDay, request.quotaPerDay),
                request = request,
                timestamp = timestamp
            ),
            monthlyQuota = fallbackStaticQuota(
                period = QuotaPeriod.MONTHLY,
                limit = preferredLimit(rateLimitProperties.staticQuotaPerMonth, request.quotaPerMonth),
                request = request,
                timestamp = timestamp
            )
        )
    }

    /**
     * ALLOW/BLOCK 정책에서 단순 윈도우 결과 반환
     */
    private fun fallbackFixedWindowResult(allowed: Boolean, limit: Long?): WindowResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) return null
        return WindowResult(
            allowed = allowed,
            effectiveCount = if (allowed) 0 else normalized,
            currentBucketCount = 0,
            previousBucketCount = 0
        )
    }

    /**
     * ALLOW/BLOCK 정책에서 단순 쿼터 결과 반환
     */
    private fun fallbackFixedQuotaResult(allowed: Boolean, limit: Long?): QuotaResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) return null
        return QuotaResult(
            allowed = allowed,
            totalCount = if (allowed) 0 else normalized
        )
    }

    /**
     * 정적 윈도우 한도를 인메모리 카운터로 평가
     */
    private fun fallbackStaticWindow(
        window: RateLimitWindow,
        limit: Long?,
        request: RateLimitScriptRequest,
        timestamp: Instant
    ): WindowResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) return null

        val bucketStart = window.bucketStart(timestamp)
        val expiresAt = bucketStart.plusSeconds(window.durationSeconds)
        val key = "fallback:${redisKeyBuilder.rateLimitKey(request.dimensions, window, bucketStart)}"

        val bucket = fallbackBuckets.compute(key) { _, existing ->
            if (existing == null || existing.expiresAt.isBefore(timestamp) || existing.expiresAt.isBefore(expiresAt)) {
                FallbackBucket(expiresAt, AtomicLong(0))
            } else {
                existing
            }
        }!!

        val current = bucket.counter.addAndGet(request.increment)
        val allowed = current <= normalized
        return WindowResult(
            allowed = allowed,
            effectiveCount = current,
            currentBucketCount = current,
            previousBucketCount = 0
        )
    }

    /**
     * 정적 쿼터를 인메모리 카운터로 평가
     */
    private fun fallbackStaticQuota(
        period: QuotaPeriod,
        limit: Long?,
        request: RateLimitScriptRequest,
        timestamp: Instant
    ): QuotaResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) return null

        val expiresAt = when (period) {
            QuotaPeriod.DAILY -> timestamp.plusSeconds(secondsUntilEndOfDay(timestamp))
            QuotaPeriod.MONTHLY -> timestamp.plusSeconds(secondsUntilEndOfMonth(timestamp))
        }
        val key = "fallback:${redisKeyBuilder.quotaKey(request.dimensions, period, timestamp)}"

        val bucket = fallbackBuckets.compute(key) { _, existing ->
            if (existing == null || existing.expiresAt.isBefore(timestamp) || existing.expiresAt.isBefore(expiresAt)) {
                FallbackBucket(expiresAt, AtomicLong(0))
            } else {
                existing
            }
        }!!

        val total = bucket.counter.addAndGet(request.increment)
        val allowed = total <= normalized
        return QuotaResult(
            allowed = allowed,
            totalCount = total
        )
    }

    /**
     * 정적 설정이 우선이며 없으면 요청 값을 사용
     */
    private fun preferredLimit(staticValue: Long?, requestValue: Long?): Long? = staticValue ?: requestValue

    /**
     * 만료된 인메모리 버킷을 제거해 메모리 누수를 방지
     */
    private fun cleanupExpiredFallbackBuckets(now: Instant) {
        fallbackBuckets.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
}

private data class FallbackBucket(
    val expiresAt: Instant,
    val counter: AtomicLong
)
