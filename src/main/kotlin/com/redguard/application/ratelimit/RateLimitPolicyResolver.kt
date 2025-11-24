package com.redguard.application.ratelimit

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.Plan
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rate Limit 정책 스냅샷을 조회하는 리졸버
 * - 테넌트 활성 상태 및 요금제 존재 여부를 검증
 * - 테넌트/플랜 단위 ApiPolicy 오버라이드를 반영해 최종 한도 값을 도출
 */
@Service
class RateLimitPolicyResolver(
    private val tenantRepository: TenantRepository,
    private val apiPolicyRepository: ApiPolicyRepository
) {

    private val logger = KotlinLogging.logger {}

    /**
     * 테넌트/플랜 상태를 검증하고 오버라이드 정책을 합성해 RateLimit 스냅샷을 반환
     */
    @Transactional(readOnly = true)
    fun resolve(command: RateLimitPolicyResolveCommand): RateLimitPolicySnapshot {
        val tenant = tenantRepository.findByName(command.tenantId)
            ?: throw RedGuardException(ErrorCode.POLICY_NOT_FOUND, "테넌트(${command.tenantId})에 대한 Rate Limit 정책을 찾을 수 없습니다.")

        if (tenant.status != TenantStatus.ACTIVE) {
            throw RedGuardException(ErrorCode.FORBIDDEN, "비활성화된 테넌트로 요청을 처리할 수 없습니다. status=${tenant.status}")
        }

        val plan = tenant.plan

        val tenantPolicy = command.apiPath?.let { path ->
            apiPolicyRepository.findByTenantIdAndHttpMethodAndApiPattern(
                requireNotNull(tenant.id) { "영속화되지 않은 테넌트 엔티티입니다." },
                command.httpMethod,
                path
            )
        }
        val planPolicy = command.apiPath?.let { path ->
            apiPolicyRepository.findByPlanIdAndHttpMethodAndApiPattern(
                requirePlanId(plan),
                command.httpMethod,
                path
            )
        }

        val snapshot = RateLimitPolicySnapshot(
            limitPerSecond = resolveLimit(tenantPolicy?.rateLimitPerSecond, planPolicy?.rateLimitPerSecond, plan.rateLimitPerSecond),
            limitPerMinute = resolveLimit(tenantPolicy?.rateLimitPerMinute, planPolicy?.rateLimitPerMinute, plan.rateLimitPerMinute),
            limitPerDay = resolveLimit(tenantPolicy?.rateLimitPerDay, planPolicy?.rateLimitPerDay, plan.rateLimitPerDay),
            quotaPerDay = resolveLimit(tenantPolicy?.quotaPerDay, planPolicy?.quotaPerDay, plan.quotaPerDay),
            quotaPerMonth = resolveLimit(tenantPolicy?.quotaPerMonth, planPolicy?.quotaPerMonth, plan.quotaPerMonth)
        )

        logger.debug { "RateLimit 정책 스냅샷 조회 완료 tenant=${command.tenantId} apiPath=${command.apiPath} method=${command.httpMethod} snapshot=$snapshot" }
        return snapshot
    }

    /**
     * 테넌트/플랜 오버라이드와 플랜 기본값을 우선순위에 따라 병합
     */
    private fun resolveLimit(
        tenantOverride: Long?,
        planOverride: Long?,
        planDefault: Long?
    ): Long? = tenantOverride ?: planOverride ?: planDefault

    /**
     * 플랜이 영속 상태인지 확인해 ID를 보장
     */
    private fun requirePlanId(plan: Plan): Long = requireNotNull(plan.id) { "영속화되지 않은 요금제 엔티티입니다." }
}

data class RateLimitPolicyResolveCommand(
    val tenantId: String,
    val apiPath: String?,
    val httpMethod: ApiHttpMethod
)
