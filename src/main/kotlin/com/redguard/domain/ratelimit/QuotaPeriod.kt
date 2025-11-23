package com.redguard.domain.ratelimit

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Quota 키에 사용되는 기간 버킷을 정의
 * UTC 캘린더 기준으로 일/월을 포맷팅
 */
enum class QuotaPeriod {
    DAILY,
    MONTHLY;

    fun bucket(timestamp: Instant): String {
        val utcDate = timestamp.atZone(ZoneOffset.UTC).toLocalDate()
        return when (this) {
            DAILY -> DAILY_FORMATTER.format(utcDate)
            MONTHLY -> MONTHLY_FORMATTER.format(YearMonth.from(utcDate))
        }
    }

    companion object {
        private val DAILY_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val MONTHLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}
