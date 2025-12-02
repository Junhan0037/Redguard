package com.redguard.api.controller.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.plan.PlanCreateRequest
import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.plan.CreatePlanCommand
import com.redguard.application.plan.PlanInfo
import com.redguard.application.plan.PlanManagementUseCase
import com.redguard.application.plan.UpdatePlanCommand
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.admin.AdminRole
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import java.time.Instant

@WebMvcTest(PlanAdminController::class)
@Import(PlanAdminControllerTest.TestConfig::class, PlanAdminControllerTest.SecurityResolverConfig::class)
@ActiveProfiles("test")
class PlanAdminControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val stubUseCase: StubPlanManagementUseCase
) {

    @AfterEach
    fun tearDown() {
        stubUseCase.reset()
    }

    @Test
    fun `요금제를_생성하면_201을_반환한다`() {
        stubUseCase.nextPlan = samplePlanInfo(1L, "PRO")

        mockMvc.post("/admin/plans") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                PlanCreateRequest(
                    name = "PRO",
                    description = "plan",
                    rateLimitPerSecond = 1,
                    rateLimitPerMinute = 60,
                    rateLimitPerDay = 1000,
                    quotaPerDay = 5000,
                    quotaPerMonth = 100000
                )
            )
            with(adminAuthentication())
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.name") { value("PRO") }
            header { string("Location", "/admin/plans/1") }
        }
    }

    @Test
    fun `검증오류면_400을_반환한다`() {
        val request = PlanCreateRequest(
            name = "",
            description = "plan",
            rateLimitPerSecond = -1,
            rateLimitPerMinute = 60,
            rateLimitPerDay = 1000,
            quotaPerDay = 5000,
            quotaPerMonth = 100000
        )

        mockMvc.post("/admin/plans") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(adminAuthentication())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `존재하지_않는_요금제면_404를_반환한다`() {
        stubUseCase.nextException = RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "not found")

        mockMvc.get("/admin/plans/99") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `삭제하면_OK를_반환한다`() {
        mockMvc.delete("/admin/plans/1") {
            accept = MediaType.APPLICATION_JSON
            with(adminAuthentication())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("OK") }
        }
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun stubPlanManagementUseCase(): StubPlanManagementUseCase = StubPlanManagementUseCase()
    }

    @TestConfiguration
    class SecurityResolverConfig : WebMvcConfigurer {
        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers.add(AuthenticationPrincipalArgumentResolver())
        }
    }

    private fun samplePlanInfo(id: Long, name: String): PlanInfo = PlanInfo(
        id = id,
        name = name,
        description = "$name plan",
        rateLimitPerSecond = 1,
        rateLimitPerMinute = 60,
        rateLimitPerDay = 1000,
        quotaPerDay = 5000,
        quotaPerMonth = 100000,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun adminAuthentication() = run {
        val principal = AdminPrincipal(1L, "admin", setOf(AdminRole.ADMIN))
        authentication(UsernamePasswordAuthenticationToken(principal, null, principal.authorities))
    }
}

class StubPlanManagementUseCase : PlanManagementUseCase {
    var nextPlan: PlanInfo? = null
    var nextList: List<PlanInfo> = emptyList()
    var nextException: RuntimeException? = null

    override fun create(command: CreatePlanCommand, auditContext: AdminAuditContext?): PlanInfo = respond()

    override fun get(planId: Long): PlanInfo = respond()

    override fun list(): List<PlanInfo> {
        nextException?.let { throw it }
        return nextList
    }

    override fun update(planId: Long, command: UpdatePlanCommand, auditContext: AdminAuditContext?): PlanInfo = respond()

    override fun delete(planId: Long, auditContext: AdminAuditContext?) {
        nextException?.let { throw it }
    }

    private fun respond(): PlanInfo {
        nextException?.let { throw it }
        return nextPlan ?: error("테스트용 PlanInfo가 설정되지 않았습니다.")
    }

    fun reset() {
        nextPlan = null
        nextList = emptyList()
        nextException = null
    }
}
