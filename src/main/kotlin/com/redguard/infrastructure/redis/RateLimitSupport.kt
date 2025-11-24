package com.redguard.infrastructure.redis

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Rate Limit 계산에서 반복 사용하는 보조 유틸리티
 */
internal fun normalizedLimit(limit: Long?): Long = limit?.takeIf { it > 0 } ?: -1

/**
 * UTC 기준 다음 날 0시까지 남은 초를 계산
 */
internal fun secondsUntilEndOfDay(timestamp: Instant): Long {
    val startOfNextDay = timestamp.atZone(ZoneOffset.UTC)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC)
    return Duration.between(timestamp, startOfNextDay).seconds.coerceAtLeast(1)
}

/**
 * UTC 기준 다음 달 1일 0시까지 남은 초를 계산
 */
internal fun secondsUntilEndOfMonth(timestamp: Instant): Long {
    val startOfNextMonth = timestamp.atZone(ZoneOffset.UTC)
        .toLocalDate()
        .withDayOfMonth(1)
        .plusMonths(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC)
    return Duration.between(timestamp, startOfNextMonth).seconds.coerceAtLeast(1)
}
