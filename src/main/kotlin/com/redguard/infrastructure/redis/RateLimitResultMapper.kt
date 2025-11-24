package com.redguard.infrastructure.redis

import org.springframework.stereotype.Component

/**
 * Lua 실행 결과(List<Long>)를 도메인 결과 모델로 변환하는 맵퍼
 */
@Component
class RateLimitResultMapper {

    /**
     * 요청 설정을 고려해 비활성화된 윈도우/쿼터는 null로 매핑
     */
    fun map(request: RateLimitScriptRequest, raw: List<Long>): RateLimitScriptResult {
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
     * 윈도우 별 4개 슬롯을 WindowResult로 변환하는 함수 팩토리를 반환
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
     * 쿼터 결과 두 슬롯을 QuotaResult로 변환하는 함수 팩토리를 반환
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
}
