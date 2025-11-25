package com.redguard.api.controller.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.plan.PlanCreateRequest
import com.redguard.application.plan.CreatePlanCommand
import com.redguard.application.plan.PlanInfo
import com.redguard.application.plan.PlanManagementUseCase
import com.redguard.application.plan.UpdatePlanCommand
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(PlanAdminController::class)
@Import(PlanAdminControllerTest.TestConfig::class)
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
}

class StubPlanManagementUseCase : PlanManagementUseCase {
    var nextPlan: PlanInfo? = null
    var nextList: List<PlanInfo> = emptyList()
    var nextException: RuntimeException? = null

    override fun create(command: CreatePlanCommand): PlanInfo = respond()

    override fun get(planId: Long): PlanInfo = respond()

    override fun list(): List<PlanInfo> {
        nextException?.let { throw it }
        return nextList
    }

    override fun update(planId: Long, command: UpdatePlanCommand): PlanInfo = respond()

    override fun delete(planId: Long) {
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
