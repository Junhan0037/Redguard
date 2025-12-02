package com.redguard.application.plan

import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.policy.PolicyAuditService
import com.redguard.application.policy.PolicyChangeType
import com.redguard.application.policy.PolicyResourceType
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.tenant.TenantRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface PlanManagementUseCase {
    fun create(command: CreatePlanCommand, auditContext: AdminAuditContext? = null): PlanInfo
    fun get(planId: Long): PlanInfo
    fun list(): List<PlanInfo>
    fun update(planId: Long, command: UpdatePlanCommand, auditContext: AdminAuditContext? = null): PlanInfo
    fun delete(planId: Long, auditContext: AdminAuditContext? = null)
}

/**
 * 요금제 CRUD 및 참조 검증을 처리하는 서비스
 */
@Service
class PlanManagementService(
    private val planRepository: PlanRepository,
    private val tenantRepository: TenantRepository,
    private val apiPolicyRepository: ApiPolicyRepository,
    private val policyAuditService: PolicyAuditService
) : PlanManagementUseCase {

    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun create(command: CreatePlanCommand, auditContext: AdminAuditContext?): PlanInfo {
        ensureNameAvailable(command.name)

        val savedPlan = saveSafely {
            planRepository.saveAndFlush(
                Plan(
                    name = command.name,
                    description = command.description,
                    rateLimitPerSecond = command.rateLimitPerSecond,
                    rateLimitPerMinute = command.rateLimitPerMinute,
                    rateLimitPerDay = command.rateLimitPerDay,
                    quotaPerDay = command.quotaPerDay,
                    quotaPerMonth = command.quotaPerMonth
                )
            )
        }

        logger.info { "요금제를 생성했습니다 planId=${savedPlan.id} name=${savedPlan.name}" }
        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.PLAN,
            resourceId = requireNotNull(savedPlan.id).toString(),
            changeType = PolicyChangeType.CREATED,
            before = null,
            after = savedPlan.toAuditPayload(),
            auditContext = auditContext
        )

        return savedPlan.toInfo()
    }

    @Transactional(readOnly = true)
    override fun get(planId: Long): PlanInfo =
        findPlan(planId).toInfo()

    @Transactional(readOnly = true)
    override fun list(): List<PlanInfo> =
        planRepository.findAll()
            .sortedBy { it.id }
            .map { it.toInfo() }

    @Transactional
    override fun update(planId: Long, command: UpdatePlanCommand, auditContext: AdminAuditContext?): PlanInfo {
        val plan = findPlan(planId)
        val beforeSnapshot = plan.toAuditPayload()
        if (plan.name != command.name) {
            ensureNameAvailable(command.name, planId)
        }

        plan.name = command.name
        plan.description = command.description
        plan.rateLimitPerSecond = command.rateLimitPerSecond
        plan.rateLimitPerMinute = command.rateLimitPerMinute
        plan.rateLimitPerDay = command.rateLimitPerDay
        plan.quotaPerDay = command.quotaPerDay
        plan.quotaPerMonth = command.quotaPerMonth

        val updatedPlan = saveSafely { planRepository.saveAndFlush(plan) }
        logger.info { "요금제를 수정했습니다 planId=${plan.id} name=${plan.name}" }

        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.PLAN,
            resourceId = requireNotNull(updatedPlan.id).toString(),
            changeType = PolicyChangeType.UPDATED,
            before = beforeSnapshot,
            after = updatedPlan.toAuditPayload(),
            auditContext = auditContext
        )
        return updatedPlan.toInfo()
    }

    @Transactional
    override fun delete(planId: Long, auditContext: AdminAuditContext?) {
        val plan = findPlan(planId)
        val beforeSnapshot = plan.toAuditPayload()

        enforceNoReference(planId)

        planRepository.delete(plan)
        logger.info { "요금제를 삭제했습니다 planId=$planId" }

        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.PLAN,
            resourceId = planId.toString(),
            changeType = PolicyChangeType.DELETED,
            before = beforeSnapshot,
            after = null,
            auditContext = auditContext
        )
    }

    private fun findPlan(planId: Long): Plan =
        planRepository.findById(planId).orElseThrow {
            RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "요금제를 찾을 수 없습니다. id=$planId")
        }

    private fun ensureNameAvailable(name: String, excludeId: Long? = null) {
        planRepository.findByName(name)?.let {
            if (excludeId == null || it.id != excludeId) {
                throw RedGuardException(ErrorCode.INVALID_REQUEST, "이미 사용 중인 요금제 이름입니다. name=$name")
            }
        }
    }

    private fun enforceNoReference(planId: Long) {
        if (tenantRepository.countByPlanId(planId) > 0) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "해당 요금제를 사용하는 테넌트가 있어 삭제할 수 없습니다.")
        }
        if (apiPolicyRepository.existsByPlanId(planId)) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "해당 요금제를 참조하는 ApiPolicy가 있어 삭제할 수 없습니다.")
        }
    }

    private fun Plan.toInfo(): PlanInfo = PlanInfo(
        id = requireNotNull(id) { "영속화되지 않은 요금제 엔티티입니다." },
        name = name,
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
     * 감사 로그 및 구조화 로그에서 활용할 요금제 스냅샷을 생성
     */
    private fun Plan.toAuditPayload() = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "rateLimitPerSecond" to rateLimitPerSecond,
        "rateLimitPerMinute" to rateLimitPerMinute,
        "rateLimitPerDay" to rateLimitPerDay,
        "quotaPerDay" to quotaPerDay,
        "quotaPerMonth" to quotaPerMonth
    )

    /**
     * JPA 저장 시 무결성 예외를 도메인 예외로 치환해 호출 측에서 일관되게 처리하도록 함
     */
    private fun <T> saveSafely(action: () -> T): T =
        try {
            action()
        } catch (ex: DataIntegrityViolationException) {
            logger.warn(ex) { "요금제를 저장하는 중 무결성 제약에 위배되었습니다." }
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "요금제를 저장하는 중 충돌이 발생했습니다. 입력값을 확인해주세요.")
        }
}
