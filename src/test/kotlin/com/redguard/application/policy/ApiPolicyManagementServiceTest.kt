package com.redguard.application.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.redguard.application.admin.AdminAuditLogger
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
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
    ApiPolicyManagementService::class,
    PolicyAuditService::class,
    AdminAuditLogger::class,
    JpaConfig::class,
    ApiPolicyManagementServiceTest.TestConfig::class
)
class ApiPolicyManagementServiceTest(
    @Autowired private val apiPolicyManagementService: ApiPolicyManagementService,
    @Autowired private val planRepository: PlanRepository,
    @Autowired private val tenantRepository: TenantRepository,
    @Autowired private val apiPolicyRepository: ApiPolicyRepository
) {

    @Test
    fun `플랜_기준_ApiPolicy를_생성하고_조회한다`() {
        val plan = planRepository.save(samplePlan("PRO"))

        val created = apiPolicyManagementService.create(
            CreateApiPolicyCommand(
                tenantId = null,
                planId = requireNotNull(plan.id),
                httpMethod = ApiHttpMethod.GET,
                apiPattern = "/v1/report",
                description = "plan policy",
                rateLimitPerMinute = 10,
                rateLimitPerDay = 100,
                rateLimitPerSecond = null,
                quotaPerDay = null,
                quotaPerMonth = null
            )
        )

        val fetched = apiPolicyManagementService.get(created.id)
        assertThat(fetched.planId).isEqualTo(plan.id)
        assertThat(fetched.httpMethod).isEqualTo(ApiHttpMethod.GET)
    }

    @Test
    fun `테넌트_기준_ApiPolicy를_검색한다`() {
        val plan = planRepository.save(samplePlan("PRO"))
        val tenant = tenantRepository.save(
            Tenant(
                name = "tenant-a",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )

        apiPolicyManagementService.create(
            CreateApiPolicyCommand(
                tenantId = requireNotNull(tenant.id),
                planId = null,
                httpMethod = ApiHttpMethod.POST,
                apiPattern = "/v1/export",
                description = "tenant policy",
                rateLimitPerMinute = 5,
                rateLimitPerDay = 50,
                rateLimitPerSecond = null,
                quotaPerDay = null,
                quotaPerMonth = null
            )
        )

        val results = apiPolicyManagementService.search(
            ApiPolicySearchFilter(
                tenantId = tenant.id,
                planId = null,
                apiPattern = "export",
                httpMethod = ApiHttpMethod.POST
            )
        )

        assertThat(results).hasSize(1)
        assertThat(results.first().tenantId).isEqualTo(tenant.id)
    }

    @Test
    fun `대상_없이_생성하면_예외가_발생한다`() {
        val exception = assertThrows<RedGuardException> {
            apiPolicyManagementService.create(
                CreateApiPolicyCommand(
                    tenantId = null,
                    planId = null,
                    httpMethod = ApiHttpMethod.GET,
                    apiPattern = "/v1/report",
                    description = null,
                    rateLimitPerSecond = null,
                    rateLimitPerMinute = null,
                    rateLimitPerDay = null,
                    quotaPerDay = null,
                    quotaPerMonth = null
                )
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `중복된_조합으로_생성하면_예외가_발생한다`() {
        val plan = planRepository.save(samplePlan("PRO"))

        apiPolicyManagementService.create(
            CreateApiPolicyCommand(
                tenantId = null,
                planId = requireNotNull(plan.id),
                httpMethod = ApiHttpMethod.GET,
                apiPattern = "/v1/report",
                description = "plan policy",
                rateLimitPerSecond = null,
                rateLimitPerMinute = 10,
                rateLimitPerDay = 100,
                quotaPerDay = null,
                quotaPerMonth = null
            )
        )

        val exception = assertThrows<RedGuardException> {
            apiPolicyManagementService.create(
                CreateApiPolicyCommand(
                    tenantId = null,
                    planId = requireNotNull(plan.id),
                    httpMethod = ApiHttpMethod.GET,
                    apiPattern = "/v1/report",
                    description = "duplicated",
                    rateLimitPerSecond = null,
                    rateLimitPerMinute = 20,
                    rateLimitPerDay = 200,
                    quotaPerDay = null,
                    quotaPerMonth = null
                )
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(apiPolicyRepository.findAll()).hasSize(1)
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()
    }

    private fun samplePlan(name: String) = Plan(
        name = name,
        description = "$name plan",
        rateLimitPerSecond = 10,
        rateLimitPerMinute = 100,
        rateLimitPerDay = 1000,
        quotaPerDay = 5000,
        quotaPerMonth = 100_000
    )
}
