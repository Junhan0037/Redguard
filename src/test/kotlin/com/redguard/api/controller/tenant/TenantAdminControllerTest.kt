package com.redguard.api.controller.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.tenant.TenantCreateRequest
import com.redguard.api.dto.tenant.TenantPlanChangeRequest
import com.redguard.api.dto.tenant.TenantUpdateRequest
import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.plan.PlanInfo
import com.redguard.application.tenant.ChangeTenantPlanCommand
import com.redguard.application.tenant.CreateTenantCommand
import com.redguard.application.tenant.TenantInfo
import com.redguard.application.tenant.TenantManagementUseCase
import com.redguard.application.tenant.UpdateTenantCommand
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.admin.AdminRole
import com.redguard.domain.tenant.TenantStatus
import com.redguard.infrastructure.security.AdminPrincipal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.*
import java.time.Instant
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver

@WebMvcTest(TenantAdminController::class)
@Import(TenantAdminControllerTest.TestConfig::class, TenantAdminControllerTest.SecurityResolverConfig::class)
@ActiveProfiles("test")
class TenantAdminControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val stubUseCase: StubTenantManagementUseCase
) {

    @AfterEach
    fun tearDown() {
        stubUseCase.reset()
    }

    @Test
    fun `테넌트를_생성하면_201과_본문을_반환한다`() {
        stubUseCase.nextTenant = sampleTenantInfo(id = 1L, name = "tenant-a", planId = 10L, planName = "PRO")

        mockMvc.post("/admin/tenants") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                TenantCreateRequest(
                    name = "tenant-a",
                    planId = 10L,
                    status = TenantStatus.ACTIVE
                )
            )
            with(adminAuthentication())
        }.andExpect {
            status { isCreated() }
            header { string("Location", "/admin/tenants/1") }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.name") { value("tenant-a") }
            jsonPath("$.data.plan.id") { value(10) }
        }
    }

    @Test
    fun `검증오류면_400을_반환한다`() {
        val request = TenantCreateRequest(
            name = "",
            planId = -1L,
            status = TenantStatus.ACTIVE
        )

        mockMvc.post("/admin/tenants") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(adminAuthentication())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `요금제를_변경하면_200으로_응답한다`() {
        stubUseCase.nextTenant = sampleTenantInfo(id = 1L, name = "tenant-a", planId = 20L, planName = "ENTERPRISE")

        mockMvc.patch("/admin/tenants/1/plan") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                TenantPlanChangeRequest(planId = 20L)
            )
            with(adminAuthentication())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.plan.name") { value("ENTERPRISE") }
        }
    }

    @Test
    fun `존재하지_않는_테넌트면_404를_반환한다`() {
        stubUseCase.nextException = RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "not found")

        mockMvc.get("/admin/tenants/999") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `삭제하면_OK_응답을_반환한다`() {
        mockMvc.delete("/admin/tenants/1") {
            accept = MediaType.APPLICATION_JSON
            with(adminAuthentication())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("OK") }
        }
    }

    private fun sampleTenantInfo(
        id: Long,
        name: String,
        planId: Long,
        planName: String
    ): TenantInfo = TenantInfo(
        id = id,
        name = name,
        status = TenantStatus.ACTIVE,
        plan = PlanInfo(
            id = planId,
            name = planName,
            description = "$planName plan",
            rateLimitPerSecond = 10,
            rateLimitPerMinute = 100,
            rateLimitPerDay = 1000,
            quotaPerDay = 5000,
            quotaPerMonth = 100000,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        ),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun adminAuthentication() = run {
        val principal = AdminPrincipal(1L, "admin", setOf(AdminRole.ADMIN))
        authentication(UsernamePasswordAuthenticationToken(principal, null, principal.authorities))
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun stubTenantManagementUseCase(): StubTenantManagementUseCase = StubTenantManagementUseCase()
    }

    @TestConfiguration
    class SecurityResolverConfig : WebMvcConfigurer {
        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers.add(AuthenticationPrincipalArgumentResolver())
        }
    }
}

class StubTenantManagementUseCase : TenantManagementUseCase {
    var nextTenant: TenantInfo? = null
    var nextList: List<TenantInfo> = emptyList()
    var nextException: RuntimeException? = null

    override fun create(command: CreateTenantCommand, auditContext: AdminAuditContext?): TenantInfo = respond()

    override fun get(tenantId: Long): TenantInfo = respond()

    override fun list(): List<TenantInfo> {
        nextException?.let { throw it }
        return nextList
    }

    override fun update(tenantId: Long, command: UpdateTenantCommand, auditContext: AdminAuditContext?): TenantInfo = respond()

    override fun changePlan(tenantId: Long, command: ChangeTenantPlanCommand, auditContext: AdminAuditContext?): TenantInfo = respond()

    override fun delete(tenantId: Long, auditContext: AdminAuditContext?) {
        nextException?.let { throw it }
    }

    private fun respond(): TenantInfo {
        nextException?.let { throw it }
        return nextTenant ?: error("테스트용 TenantInfo가 설정되지 않았습니다.")
    }

    fun reset() {
        nextTenant = null
        nextList = emptyList()
        nextException = null
    }
}
