package com.redguard.application.ratelimit

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.ratelimit.RedisKeyDimensions
import com.redguard.infrastructure.redis.RateLimitScriptRequest
import com.redguard.infrastructure.redis.RateLimitScriptResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Rate Limit/Quota 평가 유스케이스
 * - 정책 스냅샷을 받아 Redis Lua 엔진을 호출
 * - 윈도우/쿼터별 결과를 종합해 결정 사유를 반환
 */
@Service
class RateLimitCheckService(
    private val rateLimitEngine: RateLimitEngine,
    private val rateLimitMetricsPublisher: RateLimitMetricsPublisher
) {

    private val logger = KotlinLogging.logger {}

    /**
     * 주어진 정책 스냅샷과 요청 차원으로 Rate Limit/Quota를 평가
     */
    fun check(command: RateLimitCheckCommand): RateLimitCheckResult {
        if (command.increment <= 0) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "increment는 1 이상이어야 합니다.")
        }

        val scriptRequest = command.toScriptRequest()
        val scriptResult = rateLimitEngine.evaluate(scriptRequest)
        val windowUsage = mapWindows(scriptResult, command.policy)
        val quotaUsage = mapQuotas(scriptResult, command.policy)

        val allowed = isAllowed(windowUsage, quotaUsage)
        val decision = deriveDecision(scriptResult, windowUsage, quotaUsage, allowed)
        val result = RateLimitCheckResult(
            decision = decision,
            allowed = allowed,
            windowUsages = windowUsage,
            quotaUsages = quotaUsage,
            fallbackApplied = scriptResult.fallbackApplied
        )

        // 필수 관찰성 지표를 기록해 운영 시 요청/차단 추이를 추적
        rateLimitMetricsPublisher.record(command, result)

        if (!result.allowed) {
            logger.info { "Rate limit 차단: decision=$decision scope=${command.scope} tenant=${command.tenantId} api=${command.apiPath}" }
        }

        return result
    }

    /**
     * 애플리케이션 레이어 커맨드를 Redis Lua 요청 모델로 변환
     */
    private fun RateLimitCheckCommand.toScriptRequest(): RateLimitScriptRequest {
        val dimensions = RedisKeyDimensions(
            scope = scope,
            tenantId = tenantId,
            userId = userId,
            apiPath = apiPath
        )
        return RateLimitScriptRequest(
            dimensions = dimensions,
            timestamp = timestamp,
            limitPerSecond = policy.limitPerSecond,
            limitPerMinute = policy.limitPerMinute,
            limitPerDay = policy.limitPerDay,
            quotaPerDay = policy.quotaPerDay,
            quotaPerMonth = policy.quotaPerMonth,
            increment = increment
        )
    }

    /**
     * Lua 실행 결과를 윈도우 사용량 도메인 모델로 변환
     */
    private fun mapWindows(result: RateLimitScriptResult, policy: RateLimitPolicySnapshot): WindowUsages {
        return WindowUsages(
            second = result.second?.let { window ->
                WindowUsage(
                    allowed = window.allowed,
                    limit = policy.limitPerSecond,
                    effectiveCount = window.effectiveCount,
                    currentBucketCount = window.currentBucketCount,
                    previousBucketCount = window.previousBucketCount
                )
            },
            minute = result.minute?.let { window ->
                WindowUsage(
                    allowed = window.allowed,
                    limit = policy.limitPerMinute,
                    effectiveCount = window.effectiveCount,
                    currentBucketCount = window.currentBucketCount,
                    previousBucketCount = window.previousBucketCount
                )
            },
            day = result.day?.let { window ->
                WindowUsage(
                    allowed = window.allowed,
                    limit = policy.limitPerDay,
                    effectiveCount = window.effectiveCount,
                    currentBucketCount = window.currentBucketCount,
                    previousBucketCount = window.previousBucketCount
                )
            }
        )
    }

    /**
     * Lua 실행 결과를 쿼터 사용량 도메인 모델로 변환
     */
    private fun mapQuotas(result: RateLimitScriptResult, policy: RateLimitPolicySnapshot): QuotaUsages {
        return QuotaUsages(
            daily = result.dailyQuota?.let { quota ->
                QuotaUsage(
                    allowed = quota.allowed,
                    limit = policy.quotaPerDay,
                    totalCount = quota.totalCount
                )
            },
            monthly = result.monthlyQuota?.let { quota ->
                QuotaUsage(
                    allowed = quota.allowed,
                    limit = policy.quotaPerMonth,
                    totalCount = quota.totalCount
                )
            }
        )
    }

    /**
     * 모든 활성 윈도우와 쿼터가 허용 상태인지 판단
     */
    private fun isAllowed(windowUsages: WindowUsages, quotaUsages: QuotaUsages): Boolean {
        val windowAllowed = listOfNotNull(
            windowUsages.second,
            windowUsages.minute,
            windowUsages.day
        ).all { it.allowed }

        val quotaAllowed = listOfNotNull(
            quotaUsages.daily,
            quotaUsages.monthly
        ).all { it.allowed }

        return windowAllowed && quotaAllowed
    }

    /**
     * 허용/차단 여부와 폴백 여부를 기반으로 결정 사유를 도출
     */
    private fun deriveDecision(
        result: RateLimitScriptResult,
        windows: WindowUsages,
        quotas: QuotaUsages,
        allowed: Boolean
    ): RateLimitDecision {
        val quotaExceeded = listOfNotNull(quotas.daily, quotas.monthly).any { !it.allowed }
        val windowExceeded = listOfNotNull(windows.second, windows.minute, windows.day).any { !it.allowed }

        return when {
            result.fallbackApplied && allowed -> RateLimitDecision.FALLBACK_ALLOW
            result.fallbackApplied && !allowed -> RateLimitDecision.FALLBACK_BLOCK
            !allowed && quotaExceeded -> RateLimitDecision.QUOTA_EXCEEDED
            !allowed && windowExceeded -> RateLimitDecision.RATE_LIMIT_EXCEEDED
            else -> RateLimitDecision.ALLOWED
        }
    }
}
