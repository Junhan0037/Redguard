package com.redguard.application.tenant

import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.plan.PlanInfo
import com.redguard.application.policy.PolicyAuditService
import com.redguard.application.policy.PolicyChangeType
import com.redguard.application.policy.PolicyResourceType
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface TenantManagementUseCase {
    fun create(command: CreateTenantCommand, auditContext: AdminAuditContext? = null): TenantInfo
    fun get(tenantId: Long): TenantInfo
    fun list(): List<TenantInfo>
    fun update(tenantId: Long, command: UpdateTenantCommand, auditContext: AdminAuditContext? = null): TenantInfo
    fun changePlan(tenantId: Long, command: ChangeTenantPlanCommand, auditContext: AdminAuditContext? = null): TenantInfo
    fun delete(tenantId: Long, auditContext: AdminAuditContext? = null)
}

/**
 * 테넌트 CRUD 및 요금제 변경 유스케이스를 담당하는 서비스
 * - 운영 환경을 전제로 한 검증/중복 체크/로깅을 포함
 */
@Service
class TenantManagementService(
    private val tenantRepository: TenantRepository,
    private val planRepository: PlanRepository,
    private val policyAuditService: PolicyAuditService
) : TenantManagementUseCase {

    private val logger = KotlinLogging.logger {}

    /**
     * 테넌트 이름 충돌을 방지하고 유효한 요금제를 보유한 상태로 신규 생성
     */
    @Transactional
    override fun create(command: CreateTenantCommand, auditContext: AdminAuditContext?): TenantInfo {
        ensureNameAvailable(command.name)
        val plan = findPlan(command.planId)
        val tenant = Tenant(
            name = command.name,
            status = command.status,
            plan = plan
        )

        val savedTenant = saveSafely { tenantRepository.saveAndFlush(tenant) }
        logger.info { "테넌트를 생성했습니다 tenantId=${savedTenant.id} name=${savedTenant.name} plan=${plan.name}" }
        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.TENANT_PLAN,
            resourceId = requireNotNull(savedTenant.id).toString(),
            changeType = PolicyChangeType.CREATED,
            before = null,
            after = savedTenant.toAuditPayload(),
            auditContext = auditContext
        )
        return savedTenant.toInfo()
    }

    /**
     * 단일 테넌트와 연결된 요금제 정보를 함께 조회
     */
    @Transactional(readOnly = true)
    override fun get(tenantId: Long): TenantInfo {
        val tenant = findTenantWithPlan(tenantId)
        return tenant.toInfo()
    }

    /**
     * 관리 화면용 전체 테넌트 리스트 반환 (plan 페치 포함)
     */
    @Transactional(readOnly = true)
    override fun list(): List<TenantInfo> {
        return tenantRepository.findAllBy()
            .sortedBy { it.id }
            .map { it.toInfo() }
    }

    /**
     * 이름 중복 검증 후 상태/이름을 원자적으로 갱신
     */
    @Transactional
    override fun update(tenantId: Long, command: UpdateTenantCommand, auditContext: AdminAuditContext?): TenantInfo {
        val tenant = findTenantWithPlan(tenantId)
        val beforeSnapshot = tenant.toAuditPayload()
        if (tenant.name != command.name) {
            ensureNameAvailable(command.name, tenantId)
        }

        tenant.name = command.name
        tenant.status = command.status

        val updatedTenant = saveSafely { tenantRepository.saveAndFlush(tenant) }
        logger.info { "테넌트 정보를 수정했습니다 tenantId=${tenant.id} status=${tenant.status} name=${tenant.name}" }
        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.TENANT_PLAN,
            resourceId = requireNotNull(updatedTenant.id).toString(),
            changeType = PolicyChangeType.UPDATED,
            before = beforeSnapshot,
            after = updatedTenant.toAuditPayload(),
            auditContext = auditContext
        )
        return updatedTenant.toInfo()
    }

    /**
     * 동일 플랜이면 노출만, 다를 경우 요금제 변경
     */
    @Transactional
    override fun changePlan(tenantId: Long, command: ChangeTenantPlanCommand, auditContext: AdminAuditContext?): TenantInfo {
        val tenant = findTenantWithPlan(tenantId)
        val targetPlan = findPlan(command.planId)
        val beforeSnapshot = tenant.toAuditPayload()

        if (tenant.plan.id == targetPlan.id) {
            logger.info { "요금제 변경 요청이 현재 요금제와 동일합니다 tenantId=$tenantId plan=${targetPlan.name}" }
            return tenant.toInfo()
        }

        tenant.plan = targetPlan
        val updatedTenant = saveSafely { tenantRepository.saveAndFlush(tenant) }
        logger.info { "테넌트 요금제를 변경했습니다 tenantId=$tenantId plan=${targetPlan.name}" }
        policyAuditService.logPolicyChange(
            resourceType = PolicyResourceType.TENANT_PLAN,
            resourceId = tenantId.toString(),
            changeType = PolicyChangeType.UPDATED,
            before = beforeSnapshot,
            after = updatedTenant.toAuditPayload(),
            auditContext = auditContext
        )
        return updatedTenant.toInfo()
    }

    @Transactional
    override fun delete(tenantId: Long, auditContext: AdminAuditContext?) {
        // 하드 삭제 대신 상태를 INACTIVE로 전환해 비활성 처리
        val tenant = findTenantWithPlan(tenantId)
        val beforeSnapshot = tenant.toAuditPayload()
        if (tenant.status != TenantStatus.INACTIVE) {
            tenant.status = TenantStatus.INACTIVE
            val updated = saveSafely { tenantRepository.saveAndFlush(tenant) }
            logger.info { "테넌트를 비활성화했습니다 tenantId=$tenantId" }
            policyAuditService.logPolicyChange(
                resourceType = PolicyResourceType.TENANT_PLAN,
                resourceId = tenantId.toString(),
                changeType = PolicyChangeType.DELETED,
                before = beforeSnapshot,
                after = updated.toAuditPayload(),
                auditContext = auditContext
            )
        } else {
            logger.info { "이미 비활성화된 테넌트 요청 tenantId=$tenantId" }
        }
    }

    private fun findTenantWithPlan(id: Long): Tenant =
        tenantRepository.findWithPlanById(id)
            ?: throw RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "테넌트를 찾을 수 없습니다. id=$id")

    private fun findPlan(id: Long): Plan =
        planRepository.findById(id).orElseThrow {
            RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "요금제를 찾을 수 없습니다. id=$id")
        }

    /**
     * 테넌트 이름의 유일성을 보장해 운영 중 혼선을 방지
     */
    private fun ensureNameAvailable(name: String, excludeId: Long? = null) {
        tenantRepository.findByName(name)?.let {
            if (excludeId == null || it.id != excludeId) {
                throw RedGuardException(ErrorCode.INVALID_REQUEST, "이미 사용 중인 테넌트 이름입니다. name=$name")
            }
        }
    }

    /**
     * JPA 저장 시 무결성 예외를 도메인 예외로 치환해 호출 측에서 일관되게 처리하도록 함
     */
    private fun <T> saveSafely(action: () -> T): T =
        try {
            action()
        } catch (ex: DataIntegrityViolationException) {
            logger.warn(ex) { "테넌트 정보를 저장하는 중 무결성 제약에 위배되었습니다." }
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "테넌트 정보를 저장하는 중 충돌이 발생했습니다. 입력값을 확인해주세요.")
        }

    private fun Tenant.toInfo(): TenantInfo = TenantInfo(
        id = requireNotNull(id) { "영속화되지 않은 테넌트 엔티티입니다." },
        name = name,
        status = status,
        plan = plan.toInfo(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

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
     * 감사 로그에 필요한 테넌트 스냅샷(요금제 포함)을 반환
     */
    private fun Tenant.toAuditPayload() = mapOf(
        "id" to id,
        "name" to name,
        "status" to status.name,
        "planId" to plan.id,
        "planName" to plan.name
    )
}
