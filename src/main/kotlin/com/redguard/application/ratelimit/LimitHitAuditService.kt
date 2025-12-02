package com.redguard.application.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.domain.limit.LimitHitLog
import com.redguard.domain.limit.LimitHitLogRepository
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.tenant.TenantRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rate Limit/Quota 초과 시 구조화 로그와 Audit 로그(limit_hit_logs 테이블)를 남기는 서비스
 * - Redis/정책 평가와 분리해 장애 시에도 호출 흐름을 막지 않도록 설계
 */
@Service
class LimitHitAuditService(
    private val tenantRepository: TenantRepository,
    private val limitHitLogRepository: LimitHitLogRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    /**
     * RateLimit 결정 결과가 차단일 때 구조화 로그 + DB 감사 로그를 기록
     */
    @Transactional
    fun recordLimitExceeded(command: RateLimitCheckCommand, result: RateLimitCheckResult) {
        val reason = mapDecisionToReason(result.decision) ?: return

        val tenant = tenantRepository.findByName(command.tenantId)
        if (tenant == null) {
            logger.warn { "테넌트 정보를 찾지 못해 LimitHitLog를 남기지 못했습니다 tenantKey=${command.tenantId}" }
            return
        }
        val tenantId = tenant.id ?: run {
            logger.warn { "영속화되지 않은 테넌트라 LimitHitLog를 남길 수 없습니다 tenantKey=${command.tenantId}" }
            return
        }

        val apiPath = command.apiPath ?: "N/A"
        val entry = LimitHitLog(
            tenant = tenant,
            userId = command.userId,
            apiPath = apiPath,
            reason = reason,
            occurredAt = command.timestamp
        )

        runCatching {
            limitHitLogRepository.save(entry)
        }.onFailure { ex ->
            logger.warn(ex) { "LimitHitLog 영속화 실패 tenantId=${tenant.id} apiPath=$apiPath reason=$reason" }
        }

        val structuredPayload = mapOf(
            "event" to "limit_exceeded",
            "tenantId" to tenantId,
            "tenantKey" to command.tenantId,
            "userId" to command.userId,
            "apiPath" to apiPath,
            "scope" to command.scope.code,
            "decision" to result.decision.name,
            "reason" to reason.name.lowercase(),
            "fallbackApplied" to result.fallbackApplied,
            "windows" to mapWindowUsage(result),
            "quotas" to mapQuotaUsage(result),
            "occurredAt" to command.timestamp
        )
        logger.info { serialize(structuredPayload) }
    }

    private fun mapDecisionToReason(decision: RateLimitDecision): LimitHitReason? = when (decision) {
        RateLimitDecision.RATE_LIMIT_EXCEEDED -> LimitHitReason.RATE_LIMIT
        RateLimitDecision.QUOTA_EXCEEDED -> LimitHitReason.QUOTA
        else -> null
    }

    private fun mapWindowUsage(result: RateLimitCheckResult): Map<String, Any?> = mapOf(
        "second" to result.windowUsages.second?.let { window ->
            mapOf(
                "limit" to window.limit,
                "allowed" to window.allowed,
                "effectiveCount" to window.effectiveCount,
                "currentBucketCount" to window.currentBucketCount,
                "previousBucketCount" to window.previousBucketCount
            )
        },
        "minute" to result.windowUsages.minute?.let { window ->
            mapOf(
                "limit" to window.limit,
                "allowed" to window.allowed,
                "effectiveCount" to window.effectiveCount,
                "currentBucketCount" to window.currentBucketCount,
                "previousBucketCount" to window.previousBucketCount
            )
        },
        "day" to result.windowUsages.day?.let { window ->
            mapOf(
                "limit" to window.limit,
                "allowed" to window.allowed,
                "effectiveCount" to window.effectiveCount,
                "currentBucketCount" to window.currentBucketCount,
                "previousBucketCount" to window.previousBucketCount
            )
        }
    )

    private fun mapQuotaUsage(result: RateLimitCheckResult): Map<String, Any?> = mapOf(
        "daily" to result.quotaUsages.daily?.let { quota ->
            mapOf(
                "limit" to quota.limit,
                "allowed" to quota.allowed,
                "totalCount" to quota.totalCount
            )
        },
        "monthly" to result.quotaUsages.monthly?.let { quota ->
            mapOf(
                "limit" to quota.limit,
                "allowed" to quota.allowed,
                "totalCount" to quota.totalCount
            )
        }
    )

    /**
     * JSON 직렬화 실패 시에도 로그 유실을 막기 위해 toString()으로 폴백
     */
    private fun serialize(payload: Any): String =
        runCatching { objectMapper.writeValueAsString(payload) }
            .getOrElse { payload.toString() }
}
