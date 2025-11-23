package com.redguard.domain.ratelimit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Rate Limit 키에 사용되는 시간 버킷 포맷을 정의
 * UTC 기준으로 잘라낸 뒤 포맷팅
 */
enum class RateLimitWindow(
    private val formatter: DateTimeFormatter,
    private val truncateUnit: ChronoUnit,
    val durationSeconds: Long
) {
    SECOND(
        formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC),
        truncateUnit = ChronoUnit.SECONDS,
        durationSeconds = 1L
    ),
    MINUTE(
        formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC),
        truncateUnit = ChronoUnit.MINUTES,
        durationSeconds = 60L
    ),
    DAY(
        formatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC),
        truncateUnit = ChronoUnit.DAYS,
        durationSeconds = 86_400L
    );

    fun bucket(timestamp: Instant): String {
        val truncated = timestamp.truncatedTo(truncateUnit)
        return formatter.format(truncated)
    }

    fun bucketStart(timestamp: Instant): Instant = timestamp.truncatedTo(truncateUnit)
}
