package com.redguard.api.controller.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.policy.ApiPolicyCreateRequest
import com.redguard.application.policy.*
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.policy.ApiHttpMethod
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

@WebMvcTest(ApiPolicyAdminController::class)
@Import(ApiPolicyAdminControllerTest.TestConfig::class)
class ApiPolicyAdminControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val stubUseCase: StubApiPolicyManagementUseCase
) {

    @AfterEach
    fun tearDown() {
        stubUseCase.reset()
    }

    @Test
    fun `정책을_생성하면_201을_반환한다`() {
        stubUseCase.nextPolicy = samplePolicyInfo(1L)

        mockMvc.post("/admin/api-policies") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                ApiPolicyCreateRequest(
                    tenantId = 1L,
                    planId = null,
                    httpMethod = ApiHttpMethod.GET,
                    apiPattern = "/v1/report",
                    description = "policy",
                    rateLimitPerSecond = 1,
                    rateLimitPerMinute = 10,
                    rateLimitPerDay = 100,
                    quotaPerDay = 1000,
                    quotaPerMonth = 2000
                )
            )
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.apiPattern") { value("/v1/report") }
            header { string("Location", "/admin/api-policies/1") }
        }
    }

    @Test
    fun `검증오류면_400을_반환한다`() {
        val request = ApiPolicyCreateRequest(
            tenantId = null,
            planId = null,
            httpMethod = ApiHttpMethod.GET,
            apiPattern = "",
            description = null,
            rateLimitPerSecond = -1,
            rateLimitPerMinute = 10,
            rateLimitPerDay = 100,
            quotaPerDay = 1000,
            quotaPerMonth = 2000
        )

        mockMvc.post("/admin/api-policies") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `존재하지_않으면_404를_반환한다`() {
        stubUseCase.nextException = RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "not found")

        mockMvc.get("/admin/api-policies/99") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `삭제하면_OK를_반환한다`() {
        mockMvc.delete("/admin/api-policies/1") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("OK") }
        }
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun stubApiPolicyManagementUseCase(): StubApiPolicyManagementUseCase = StubApiPolicyManagementUseCase()
    }

    private fun samplePolicyInfo(id: Long): ApiPolicyInfo = ApiPolicyInfo(
        id = id,
        tenantId = 1L,
        planId = null,
        httpMethod = ApiHttpMethod.GET,
        apiPattern = "/v1/report",
        description = "policy",
        rateLimitPerSecond = 1,
        rateLimitPerMinute = 10,
        rateLimitPerDay = 100,
        quotaPerDay = 1000,
        quotaPerMonth = 2000,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}

class StubApiPolicyManagementUseCase : ApiPolicyManagementUseCase {
    var nextPolicy: ApiPolicyInfo? = null
    var nextList: List<ApiPolicyInfo> = emptyList()
    var nextException: RuntimeException? = null

    override fun create(command: CreateApiPolicyCommand): ApiPolicyInfo = respond()

    override fun update(policyId: Long, command: UpdateApiPolicyCommand): ApiPolicyInfo = respond()

    override fun get(policyId: Long): ApiPolicyInfo = respond()

    override fun search(filter: ApiPolicySearchFilter): List<ApiPolicyInfo> {
        nextException?.let { throw it }
        return nextList
    }

    override fun delete(policyId: Long) {
        nextException?.let { throw it }
    }

    private fun respond(): ApiPolicyInfo {
        nextException?.let { throw it }
        return nextPolicy ?: error("테스트용 ApiPolicyInfo가 설정되지 않았습니다.")
    }

    fun reset() {
        nextPolicy = null
        nextList = emptyList()
        nextException = null
    }
}
