package com.redguard.application.policy

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.plan.Plan
import com.redguard.domain.policy.ApiPolicy
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface ApiPolicyManagementUseCase {
    fun create(command: CreateApiPolicyCommand): ApiPolicyInfo
    fun update(policyId: Long, command: UpdateApiPolicyCommand): ApiPolicyInfo
    fun get(policyId: Long): ApiPolicyInfo
    fun search(filter: ApiPolicySearchFilter): List<ApiPolicyInfo>
    fun delete(policyId: Long)
}

/**
 * ApiPolicy CRUD 및 조회 필터를 처리하는 서비스
 */
@Service
class ApiPolicyManagementService(
    private val apiPolicyRepository: ApiPolicyRepository,
    private val tenantRepository: TenantRepository,
    private val planRepository: PlanRepository
) : ApiPolicyManagementUseCase {

    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun create(command: CreateApiPolicyCommand): ApiPolicyInfo {
        val target = validateAndResolveTarget(command.tenantId, command.planId)
        ensureUniquePolicy(target.tenantId, target.planId, command.httpMethod, command.apiPattern, null)

        val entity = ApiPolicy(
            tenant = target.tenant,
            plan = target.plan,
            httpMethod = command.httpMethod,
            apiPattern = command.apiPattern,
            description = command.description,
            rateLimitPerSecond = command.rateLimitPerSecond,
            rateLimitPerMinute = command.rateLimitPerMinute,
            rateLimitPerDay = command.rateLimitPerDay,
            quotaPerDay = command.quotaPerDay,
            quotaPerMonth = command.quotaPerMonth
        )

        val saved = saveSafely { apiPolicyRepository.saveAndFlush(entity) }
        logger.info { "ApiPolicy를 생성했습니다 policyId=${saved.id} tenantId=${saved.tenant?.id} planId=${saved.plan?.id} method=${saved.httpMethod} pattern=${saved.apiPattern}" }
        return saved.toInfo()
    }

    @Transactional
    override fun update(policyId: Long, command: UpdateApiPolicyCommand): ApiPolicyInfo {
        val policy = findPolicy(policyId)
        val target = validateAndResolveTarget(command.tenantId, command.planId)
        ensureUniquePolicy(target.tenantId, target.planId, command.httpMethod, command.apiPattern, policyId)

        policy.tenant = target.tenant
        policy.plan = target.plan
        policy.httpMethod = command.httpMethod
        policy.apiPattern = command.apiPattern
        policy.description = command.description
        policy.rateLimitPerSecond = command.rateLimitPerSecond
        policy.rateLimitPerMinute = command.rateLimitPerMinute
        policy.rateLimitPerDay = command.rateLimitPerDay
        policy.quotaPerDay = command.quotaPerDay
        policy.quotaPerMonth = command.quotaPerMonth

        val updated = saveSafely { apiPolicyRepository.saveAndFlush(policy) }
        logger.info { "ApiPolicy를 수정했습니다 policyId=${policy.id} tenantId=${policy.tenant?.id} planId=${policy.plan?.id} method=${policy.httpMethod} pattern=${policy.apiPattern}" }
        return updated.toInfo()
    }

    @Transactional(readOnly = true)
    override fun get(policyId: Long): ApiPolicyInfo = findPolicy(policyId).toInfo()

    @Transactional(readOnly = true)
    override fun search(filter: ApiPolicySearchFilter): List<ApiPolicyInfo> {
        val spec = buildSpecification(filter)
        return apiPolicyRepository.findAll(spec)
            .sortedBy { it.id }
            .map { it.toInfo() }
    }

    @Transactional
    override fun delete(policyId: Long) {
        val policy = findPolicy(policyId)
        apiPolicyRepository.delete(policy)
        logger.info { "ApiPolicy를 삭제했습니다 policyId=$policyId" }
    }

    private fun validateAndResolveTarget(tenantId: Long?, planId: Long?): PolicyTarget {
        if (tenantId == null && planId == null) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "테넌트 또는 요금제 중 하나는 반드시 지정해야 합니다.")
        }
        if (tenantId != null && planId != null) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "테넌트 정책과 요금제 정책을 동시에 지정할 수 없습니다.")
        }

        val tenant = tenantId?.let {
            tenantRepository.findWithPlanById(it)
                ?: throw RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "테넌트를 찾을 수 없습니다. id=$it")
        }
        val plan = planId?.let {
            planRepository.findById(it).orElseThrow {
                RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "요금제를 찾을 수 없습니다. id=$it")
            }
        }
        return PolicyTarget(tenant, plan)
    }

    private fun ensureUniquePolicy(
        tenantId: Long?,
        planId: Long?,
        httpMethod: ApiHttpMethod,
        apiPattern: String,
        excludeId: Long?
    ) {
        val duplicated = when {
            tenantId != null -> apiPolicyRepository.findByTenantIdAndHttpMethodAndApiPattern(tenantId, httpMethod, apiPattern)
            planId != null -> apiPolicyRepository.findByPlanIdAndHttpMethodAndApiPattern(planId, httpMethod, apiPattern)
            else -> null
        }

        duplicated?.let {
            if (excludeId == null || it.id != excludeId) {
                throw RedGuardException(ErrorCode.INVALID_REQUEST, "이미 동일한 대상에 대한 ApiPolicy가 존재합니다.")
            }
        }
    }

    private fun buildSpecification(filter: ApiPolicySearchFilter): Specification<ApiPolicy> {
        var spec: Specification<ApiPolicy> = Specification.allOf()

        filter.tenantId?.let { tenantId ->
            spec = spec.and { root, _, cb -> cb.equal(root.get<Long>("tenant").get<Long>("id"), tenantId) }
        }
        filter.planId?.let { planId ->
            spec = spec.and { root, _, cb -> cb.equal(root.get<Long>("plan").get<Long>("id"), planId) }
        }
        filter.apiPattern?.let { pattern ->
            spec = spec.and { root, _, cb -> cb.like(root.get("apiPattern"), "%$pattern%") }
        }
        filter.httpMethod?.let { method ->
            spec = spec.and { root, _, cb -> cb.equal(root.get<ApiHttpMethod>("httpMethod"), method) }
        }
        return spec
    }

    private fun findPolicy(policyId: Long): ApiPolicy =
        apiPolicyRepository.findById(policyId).orElseThrow {
            RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "ApiPolicy를 찾을 수 없습니다. id=$policyId")
        }

    private fun ApiPolicy.toInfo() = ApiPolicyInfo(
        id = requireNotNull(id) { "영속화되지 않은 ApiPolicy 엔티티입니다." },
        tenantId = tenant?.id,
        planId = plan?.id,
        httpMethod = httpMethod,
        apiPattern = apiPattern,
        description = description,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * JPA 저장 시 무결성 예외를 도메인 예외로 치환해 호출 측에서 일관되게 처리하도록 함
     */
    private fun <T> saveSafely(action: () -> T): T =
        try {
            action()
        } catch (ex: DataIntegrityViolationException) {
            logger.warn(ex) { "ApiPolicy를 저장하는 중 무결성 제약에 위배되었습니다." }
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "ApiPolicy를 저장하는 중 충돌이 발생했습니다. 입력값을 확인해주세요.")
        }
}

private data class PolicyTarget(
    val tenant: Tenant?,
    val plan: Plan?
) {
    val tenantId: Long? get() = tenant?.id
    val planId: Long? get() = plan?.id
}
