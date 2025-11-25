package com.redguard.application.plan

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.policy.ApiPolicy
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import com.redguard.infrastructure.config.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(PlanManagementService::class, JpaConfig::class)
class PlanManagementServiceTest(
    @Autowired private val planManagementService: PlanManagementService,
    @Autowired private val planRepository: PlanRepository,
    @Autowired private val tenantRepository: TenantRepository,
    @Autowired private val apiPolicyRepository: ApiPolicyRepository
) {

    @Test
    fun `요금제를_생성하고_조회한다`() {
        val created = planManagementService.create(
            CreatePlanCommand(
                name = "PRO",
                description = "pro plan",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 100,
                rateLimitPerDay = 1000,
                quotaPerDay = 5000,
                quotaPerMonth = 100_000
            )
        )

        assertThat(created.id).isNotNull
        assertThat(planManagementService.get(created.id).name).isEqualTo("PRO")
        assertThat(planManagementService.list()).hasSize(1)
    }

    @Test
    fun `중복된_이름으로_생성하면_예외가_발생한다`() {
        planManagementService.create(
            CreatePlanCommand(
                name = "PRO",
                description = "pro plan",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 100,
                rateLimitPerDay = 1000,
                quotaPerDay = 5000,
                quotaPerMonth = 100_000
            )
        )

        val exception = assertThrows<RedGuardException> {
            planManagementService.create(
                CreatePlanCommand(
                    name = "PRO",
                    description = "duplicated",
                    rateLimitPerSecond = 1,
                    rateLimitPerMinute = 10,
                    rateLimitPerDay = 100,
                    quotaPerDay = 50,
                    quotaPerMonth = 1000
                )
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `테넌트가_참조하는_요금제는_삭제할_수_없다`() {
        val plan = persistPlan("PRO")
        tenantRepository.save(
            Tenant(
                name = "tenant-a",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )

        val exception = assertThrows<RedGuardException> {
            planManagementService.delete(requireNotNull(plan.id))
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `ApiPolicy가_참조하는_요금제는_삭제할_수_없다`() {
        val plan = persistPlan("PRO")
        apiPolicyRepository.save(
            ApiPolicy(
                plan = plan,
                httpMethod = ApiHttpMethod.GET,
                apiPattern = "/v1/report",
                description = "policy",
                rateLimitPerMinute = 10
            )
        )

        val exception = assertThrows<RedGuardException> {
            planManagementService.delete(requireNotNull(plan.id))
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    private fun persistPlan(name: String): com.redguard.domain.plan.Plan {
        val saved = planManagementService.create(
            CreatePlanCommand(
                name = name,
                description = "$name plan",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 100,
                rateLimitPerDay = 1000,
                quotaPerDay = 5000,
                quotaPerMonth = 100_000
            )
        )
        return planRepository.findById(saved.id).orElseThrow()
    }
}
