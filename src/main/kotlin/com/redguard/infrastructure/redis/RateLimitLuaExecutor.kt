package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitWindow
import com.redguard.domain.ratelimit.RedisKeyDimensions
import com.redguard.infrastructure.redis.script.RateLimitLuaScript
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Rate Limit/Quota Lua 스크립트를 실행하는 래퍼
 * - 슬라이딩 윈도우(초/분/일) 카운터와 일/월 Quota를 한 번에 증가
 * - Lua 스크립트 파라미터 생성 시 키 네이밍 규칙을 준수
 */
@Component
class RateLimitLuaExecutor(
    private val redisTemplate: StringRedisTemplate,
    private val redisKeyBuilder: RedisKeyBuilder
) {

    // Spring RedisScript 객체 (Lua 소스는 RateLimitLuaScript.SOURCE)
    private val script = RateLimitLuaScript.redisScript()

    @Suppress("SENSELESS_COMPARISON")
    fun evaluate(request: RateLimitScriptRequest): RateLimitScriptResult {
        // 증가량 방어적 체크
        if (request.increment <= 0) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "증가량은 1 이상이어야 합니다.")
        }

        // Lua 실행 키/인자 구성
        val payload = buildPayload(request)

        // Redis Lua 스크립트 실행
        val rawResult = redisTemplate.execute(
            script,
            payload.keys,
            *payload.args.toTypedArray()
        )

        if (rawResult == null) {
            throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 실행 결과가 없습니다.")
        }

        // 예상되는 반환 배열 길이 검증
        if (rawResult.size < 16) {
            throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 결과 형식이 올바르지 않습니다.")
        }

        return mapResult(request, rawResult)
    }

    /**
     * Lua 스크립트에 전달할 KEYS/ARGV를 생성
     */
    internal fun buildPayload(request: RateLimitScriptRequest): RateLimitScriptPayload {
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

        return RateLimitScriptPayload(keys = keys, args = args, windowPayloads = WindowPayloads(secPayload, minPayload, dayPayload))
    }

    /**
     * Lua 반환값을 도메인 결과로 매핑
     */
    private fun mapResult(request: RateLimitScriptRequest, raw: List<Long>): RateLimitScriptResult {
        val second = mapWindowResult(0, request.limitPerSecond)
        val minute = mapWindowResult(4, request.limitPerMinute)
        val day = mapWindowResult(8, request.limitPerDay)
        val quotaDay = mapQuotaResult(12, request.quotaPerDay)
        val quotaMonth = mapQuotaResult(14, request.quotaPerMonth)

        return RateLimitScriptResult(
            second = second(raw),
            minute = minute(raw),
            day = day(raw),
            dailyQuota = quotaDay(raw),
            monthlyQuota = quotaMonth(raw)
        )
    }

    /**
     * 윈도우별 결과 매퍼 (비활성화 시 null)
     */
    private fun mapWindowResult(offset: Int, limit: Long?): (List<Long>) -> WindowResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) {
            return { null }
        }
        return { raw ->
            WindowResult(
                allowed = raw[offset] == 1L,
                effectiveCount = raw[offset + 1],
                currentBucketCount = raw[offset + 2],
                previousBucketCount = raw[offset + 3]
            )
        }
    }

    /**
     * 쿼터별 결과 매퍼 (비활성화 시 null)
     */
    private fun mapQuotaResult(offset: Int, limit: Long?): (List<Long>) -> QuotaResult? {
        val normalized = normalizedLimit(limit)
        if (normalized <= 0) {
            return { null }
        }
        return { raw ->
            QuotaResult(
                allowed = raw[offset] == 1L,
                totalCount = raw[offset + 1]
            )
        }
    }

    /**
     * 윈도우별 현재/이전 키, TTL, 이전 버킷 가중치 계산
     */
    private fun windowPayload(window: RateLimitWindow, limit: Long?, timestamp: Instant, dimensions: RedisKeyDimensions): WindowPayload {
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
     * 경과 시간 비율로 이전 버킷 기여도를 계산 (2-버킷 슬라이딩 윈도우)
     */
    private fun previousBucketWeight(window: RateLimitWindow, timestamp: Instant): Double {
        val bucketStart = window.bucketStart(timestamp)
        val elapsedMillis = Duration.between(bucketStart, timestamp).toMillis().toDouble()
        val windowMillis = window.durationSeconds * 1_000.0
        val weight = 1.0 - (elapsedMillis / windowMillis)
        return weight.coerceIn(0.0, 1.0)
    }

    /**
     * UTC 기준 다음 날 0시까지 남은 초
     */
    private fun secondsUntilEndOfDay(timestamp: Instant): Long {
        val startOfNextDay = timestamp.atZone(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
        return Duration.between(timestamp, startOfNextDay).seconds.coerceAtLeast(1)
    }

    /**
     * UTC 기준 다음 달 1일 0시까지 남은 초
     */
    private fun secondsUntilEndOfMonth(timestamp: Instant): Long {
        val startOfNextMonth = timestamp.atZone(ZoneOffset.UTC)
            .toLocalDate()
            .withDayOfMonth(1)
            .plusMonths(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
        return Duration.between(timestamp, startOfNextMonth).seconds.coerceAtLeast(1)
    }

    /**
     * limit null/0/음수는 비활성화(-1)로 변환
     */
    private fun normalizedLimit(limit: Long?): Long = limit?.takeIf { it > 0 } ?: -1
}

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

data class WindowResult(
    val allowed: Boolean,
    val effectiveCount: Long,
    val currentBucketCount: Long,
    val previousBucketCount: Long
)

data class QuotaResult(
    val allowed: Boolean,
    val totalCount: Long
)

data class RateLimitScriptResult(
    val second: WindowResult?,
    val minute: WindowResult?,
    val day: WindowResult?,
    val dailyQuota: QuotaResult?,
    val monthlyQuota: QuotaResult?
)

data class RateLimitScriptPayload(
    val keys: List<String>,
    val args: List<String>,
    val windowPayloads: WindowPayloads
)

data class WindowPayload(
    val currentKey: String,
    val previousKey: String,
    val limit: Long,
    val ttlSeconds: Long,
    val previousWeight: Double
) {
    fun previousWeightAsString(): String = String.format(Locale.US, "%.6f", previousWeight)
}

data class WindowPayloads(
    val second: WindowPayload,
    val minute: WindowPayload,
    val day: WindowPayload
)
