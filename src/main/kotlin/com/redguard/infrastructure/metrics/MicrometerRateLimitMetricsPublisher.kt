package com.redguard.infrastructure.metrics

import com.redguard.application.ratelimit.RateLimitCheckCommand
import com.redguard.application.ratelimit.RateLimitCheckResult
import com.redguard.application.ratelimit.RateLimitDecision
import com.redguard.application.ratelimit.RateLimitMetricsPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Rate Limit 핵심 메트릭을 Micrometer로 기록하는 구현체
 * - 총 요청 수/허용 여부/결정 사유 및 테넌트별 Limit Hit 수를 태그 기반으로 집계
 */
@Component
class MicrometerRateLimitMetricsPublisher(
    private val meterRegistry: MeterRegistry
) : RateLimitMetricsPublisher {

    private val logger = KotlinLogging.logger {}

    /**
     * Rate Limit 평가 결과를 메트릭으로 누적하고, 실패 시 경고만 남겨 서비스 흐름을 막지 않음
     */
    override fun record(command: RateLimitCheckCommand, result: RateLimitCheckResult) {
        runCatching {
            recordRequestMetrics(command, result)
            recordLimitHitMetrics(command, result)
        }.onFailure { ex ->
            logger.warn(ex) { "RateLimit 메트릭 적재 실패: tenantId=${command.tenantId}, scope=${command.scope.code}" }
        }
    }

    /**
     * 전체 요청 수 및 허용/차단 수를 decision/outcome/scope/fallback 태그와 함께 기록
     */
    private fun recordRequestMetrics(command: RateLimitCheckCommand, result: RateLimitCheckResult) {
        val tags = Tags.of(
            "decision", result.decision.name.lowercase(Locale.ROOT),
            "outcome", if (result.allowed) "allowed" else "blocked",
            "scope", command.scope.code,
            "fallback", result.fallbackApplied.toString()
        )
        meterRegistry.counter("redguard.ratelimit.requests", tags).increment()
    }

    /**
     * Rate Limit 또는 Quota 초과로 차단된 케이스에 대해 테넌트별 Hit 메트릭을 기록
     */
    private fun recordLimitHitMetrics(command: RateLimitCheckCommand, result: RateLimitCheckResult) {
        if (result.decision != RateLimitDecision.RATE_LIMIT_EXCEEDED && result.decision != RateLimitDecision.QUOTA_EXCEEDED) {
            return
        }

        val tags = Tags.of(
            "tenantId", command.tenantId,
            "scope", command.scope.code,
            "reason", mapDecisionToReason(result.decision)
        )
        meterRegistry.counter("redguard.ratelimit.limit_hits", tags).increment()
    }

    /**
     * 메트릭 태그 값으로 사용할 결정 사유 문자열을 표준화
     */
    private fun mapDecisionToReason(decision: RateLimitDecision): String = when (decision) {
        RateLimitDecision.RATE_LIMIT_EXCEEDED -> "rate_limit"
        RateLimitDecision.QUOTA_EXCEEDED -> "quota"
        else -> "unknown"
    }
}
