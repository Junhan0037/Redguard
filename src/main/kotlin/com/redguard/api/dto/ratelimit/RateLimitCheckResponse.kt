package com.redguard.api.dto.ratelimit

import java.time.Instant

/**
 * `/internal/rate-limit/check` 응답 DTO
 * 허용 여부와 윈도우/쿼터별 사용량 정보를 함께 노출해 호출 측에서 후속 의사결정 가능
 */
data class RateLimitCheckResponse(
    val allowed: Boolean,
    val decision: DecisionReason,
    val evaluatedAt: Instant = Instant.now(),
    val windows: WindowEvaluations = WindowEvaluations(),
    val quotas: QuotaEvaluations = QuotaEvaluations(),
    val fallbackApplied: Boolean = false
)

/**
 * 차단 사유나 허용 근거를 명시하는 코드
 */
enum class DecisionReason {
    ALLOWED,
    RATE_LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    INVALID_REQUEST,
    FALLBACK_ALLOW,
    FALLBACK_BLOCK
}

/**
 * 초/분/일 윈도우별 평가 결과
 */
data class WindowEvaluations(
    val second: WindowEvaluation? = null,
    val minute: WindowEvaluation? = null,
    val day: WindowEvaluation? = null
)

/**
 * 단일 윈도우의 사용량 및 허용 여부
 */
data class WindowEvaluation(
    val allowed: Boolean,
    val limit: Long?,
    val effectiveCount: Long,
    val currentBucketCount: Long,
    val previousBucketCount: Long
)

/**
 * 일/월 쿼터별 평가 결과
 */
data class QuotaEvaluations(
    val daily: QuotaEvaluation? = null,
    val monthly: QuotaEvaluation? = null
)

/**
 * 단일 쿼터의 누적 사용량 및 허용 여부
 */
data class QuotaEvaluation(
    val allowed: Boolean,
    val limit: Long?,
    val totalCount: Long
)
