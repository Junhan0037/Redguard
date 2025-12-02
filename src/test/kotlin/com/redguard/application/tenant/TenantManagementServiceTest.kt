package com.redguard.application.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.redguard.application.admin.AdminAuditLogger
import com.redguard.application.policy.PolicyAuditService
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.tenant.TenantStatus
import com.redguard.infrastructure.config.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(
    TenantManagementService::class,
    PolicyAuditService::class,
    AdminAuditLogger::class,
    JpaConfig::class,
    TenantManagementServiceTest.TestConfig::class
)
class TenantManagementServiceTest(
    @Autowired private val tenantManagementService: TenantManagementService,
    @Autowired private val planRepository: PlanRepository
) {

    @Test
    fun `테넌트를_생성하고_조회한다`() {
        val plan = createPlan("PRO")

        val created = tenantManagementService.create(
            CreateTenantCommand(
                name = "tenant-a",
                planId = requireNotNull(plan.id),
                status = TenantStatus.ACTIVE
            )
        )

        assertThat(created.id).isNotNull
        assertThat(created.plan.name).isEqualTo("PRO")

        val fetched = tenantManagementService.get(created.id)
        assertThat(fetched.name).isEqualTo("tenant-a")
        assertThat(fetched.status).isEqualTo(TenantStatus.ACTIVE)
    }

    @Test
    fun `중복_이름이면_예외가_발생한다`() {
        val plan = createPlan("PRO")
        tenantManagementService.create(
            CreateTenantCommand(
                name = "tenant-a",
                planId = requireNotNull(plan.id),
                status = TenantStatus.ACTIVE
            )
        )

        val exception = assertThrows<RedGuardException> {
            tenantManagementService.create(
                CreateTenantCommand(
                    name = "tenant-a",
                    planId = requireNotNull(plan.id),
                    status = TenantStatus.ACTIVE
                )
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `요금제를_변경하면_새로운_플랜이_적용된다`() {
        val planA = createPlan("FREE")
        val planB = createPlan("PRO")
        val created = tenantManagementService.create(
            CreateTenantCommand(
                name = "tenant-a",
                planId = requireNotNull(planA.id),
                status = TenantStatus.ACTIVE
            )
        )

        val updated = tenantManagementService.changePlan(
            tenantId = created.id,
            command = ChangeTenantPlanCommand(planId = requireNotNull(planB.id))
        )

        assertThat(updated.plan.name).isEqualTo("PRO")
    }

    @Test
    fun `삭제하면_상태가_INACTIVE로_변경된다`() {
        val plan = createPlan("PRO")
        val created = tenantManagementService.create(
            CreateTenantCommand(
                name = "tenant-a",
                planId = requireNotNull(plan.id),
                status = TenantStatus.ACTIVE
            )
        )

        tenantManagementService.delete(created.id)

        val fetched = tenantManagementService.get(created.id)
        assertThat(fetched.status).isEqualTo(TenantStatus.INACTIVE)
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()
    }

    private fun createPlan(name: String): Plan =
        planRepository.save(
            Plan(
                name = name,
                description = "$name plan",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 100,
                rateLimitPerDay = 1000,
                quotaPerDay = 5000,
                quotaPerMonth = 100_000
            )
        )
}
