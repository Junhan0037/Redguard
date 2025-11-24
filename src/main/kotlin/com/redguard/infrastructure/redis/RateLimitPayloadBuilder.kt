package com.redguard.infrastructure.redis

import com.redguard.domain.ratelimit.RedisKeyDimensions
import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitWindow
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Component

/**
 * Rate Limit Lua 스크립트에 전달할 KEYS/ARGV를 조립하는 빌더
 */
@Component
class RateLimitPayloadBuilder(
    private val redisKeyBuilder: RedisKeyBuilder
) {

    /**
     * 요청 정보로부터 Lua 실행 페이로드를 생성
     */
    fun build(request: RateLimitScriptRequest): RateLimitScriptPayload {
        val secPayload = windowPayload(RateLimitWindow.SECOND, request.limitPerSecond, request.timestamp, request.dimensions)
        val minPayload = windowPayload(RateLimitWindow.MINUTE, request.limitPerMinute, request.timestamp, request.dimensions)
        val dayPayload = windowPayload(RateLimitWindow.DAY, request.limitPerDay, request.timestamp, request.dimensions)

        val quotaDayKey = redisKeyBuilder.quotaKey(request.dimensions, QuotaPeriod.DAILY, request.timestamp)
        val quotaMonthKey = redisKeyBuilder.quotaKey(request.dimensions, QuotaPeriod.MONTHLY, request.timestamp)
        val quotaDayTtl = secondsUntilEndOfDay(request.timestamp)
        val quotaMonthTtl = secondsUntilEndOfMonth(request.timestamp)

        val keys = listOf(
            secPayload.currentKey, secPayload.previousKey,
            minPayload.currentKey, minPayload.previousKey,
            dayPayload.currentKey, dayPayload.previousKey,
            quotaDayKey, quotaMonthKey
        )

        val args = listOf(
            secPayload.limit.toString(), minPayload.limit.toString(), dayPayload.limit.toString(),
            normalizedLimit(request.quotaPerDay).toString(), normalizedLimit(request.quotaPerMonth).toString(),
            secPayload.ttlSeconds.toString(), minPayload.ttlSeconds.toString(), dayPayload.ttlSeconds.toString(),
            quotaDayTtl.toString(), quotaMonthTtl.toString(),
            secPayload.previousWeightAsString(), minPayload.previousWeightAsString(), dayPayload.previousWeightAsString(),
            request.increment.toString()
        )

        return RateLimitScriptPayload(
            keys = keys,
            args = args,
            windowPayloads = WindowPayloads(secPayload, minPayload, dayPayload)
        )
    }

    /**
     * 윈도우별 현재/이전 키, TTL, 이전 버킷 가중치를 계산
     */
    private fun windowPayload(
        window: RateLimitWindow,
        limit: Long?,
        timestamp: Instant,
        dimensions: RedisKeyDimensions
    ): WindowPayload {
        val normalizedLimit = normalizedLimit(limit)
        val currentKey = redisKeyBuilder.rateLimitKey(dimensions, window, timestamp)
        val previousBucketTimestamp = timestamp.minus(window.durationSeconds, ChronoUnit.SECONDS)
        val previousKey = redisKeyBuilder.rateLimitKey(dimensions, window, previousBucketTimestamp)
        val ttlSeconds = window.durationSeconds * 2 // 2-버킷 슬라이딩 윈도우 유지
        val previousWeight = previousBucketWeight(window, timestamp)

        return WindowPayload(
            currentKey = currentKey,
            previousKey = previousKey,
            limit = normalizedLimit,
            ttlSeconds = ttlSeconds,
            previousWeight = previousWeight
        )
    }

    /**
     * 경과 시간 비율에 기반한 이전 버킷 기여도(2-버킷 슬라이딩 윈도우)를 계산
     */
    private fun previousBucketWeight(window: RateLimitWindow, timestamp: Instant): Double {
        val bucketStart = window.bucketStart(timestamp)
        val elapsedMillis = Duration.between(bucketStart, timestamp).toMillis().toDouble()
        val windowMillis = window.durationSeconds * 1_000.0
        val weight = 1.0 - (elapsedMillis / windowMillis)
        return weight.coerceIn(0.0, 1.0)
    }
}
