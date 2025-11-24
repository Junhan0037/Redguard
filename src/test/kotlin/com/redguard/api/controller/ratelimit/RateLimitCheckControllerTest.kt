package com.redguard.api.controller.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.ratelimit.RateLimitCheckRequest
import com.redguard.application.ratelimit.*
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.ratelimit.RateLimitScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(RateLimitCheckController::class)
@Import(RateLimitCheckControllerTest.TestConfig::class)
@ExtendWith(SpringExtension::class)
class RateLimitCheckControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val stubUseCase: StubRateLimitCheckUseCase
) {

    @AfterEach
    fun tearDown() {
        stubUseCase.reset()
    }

    @Test
    fun `허용되면 200 OK와 ALLOWED 결정을 반환한다`() {
        stubUseCase.nextResult = sampleResult(RateLimitDecision.ALLOWED, allowed = true)

        val response = mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.decision") { value("ALLOWED") }
            jsonPath("$.data.allowed") { value(true) }
        }.andReturn().response

        assertTrue(response.contentAsString.contains("ALLOWED"))
    }

    @Test
    fun `Rate limit 초과 시 429 Too Many Requests로 응답한다`() {
        stubUseCase.nextResult = sampleResult(RateLimitDecision.RATE_LIMIT_EXCEEDED, allowed = false)

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.data.decision") { value("RATE_LIMIT_EXCEEDED") }
        }
    }

    @Test
    fun `Quota 초과 시 403 Forbidden으로 응답한다`() {
        stubUseCase.nextResult = sampleResult(RateLimitDecision.QUOTA_EXCEEDED, allowed = false)

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.data.decision") { value("QUOTA_EXCEEDED") }
        }
    }

    @Test
    fun `Fallback 차단 시 503 Service Unavailable로 응답한다`() {
        stubUseCase.nextResult = sampleResult(RateLimitDecision.FALLBACK_BLOCK, allowed = false, fallback = true)

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.data.fallbackApplied") { value(true) }
            jsonPath("$.data.decision") { value("FALLBACK_BLOCK") }
        }
    }

    @Test
    fun `정책을 찾지 못하면 404 Not Found 에러 응답을 반환한다`() {
        stubUseCase.nextException = RedGuardException(ErrorCode.POLICY_NOT_FOUND, "정책 없음")

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("POLICY_NOT_FOUND") }
        }
    }

    @Test
    fun `검증 오류 시 400과 에러 상세를 반환한다`() {
        val invalidRequest = validRequest().copy(tenantId = "")

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(invalidRequest)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.details") { exists() }
        }
    }

    private fun validRequest(): RateLimitCheckRequest = RateLimitCheckRequest(
        scope = RateLimitScope.TENANT_API,
        tenantId = "tenant-1",
        userId = "user-1",
        apiPath = "/v1/report",
        httpMethod = ApiHttpMethod.GET,
        timestamp = Instant.now(),
        increment = 1
    )

    private fun sampleResult(
        decision: RateLimitDecision,
        allowed: Boolean,
        fallback: Boolean = false
    ): RateLimitCheckResult = RateLimitCheckResult(
        decision = decision,
        allowed = allowed,
        windowUsages = WindowUsages(),
        quotaUsages = QuotaUsages(),
        fallbackApplied = fallback
    )

    @TestConfiguration
    class TestConfig {
        @Bean
        fun stubRateLimitCheckUseCase(): StubRateLimitCheckUseCase = StubRateLimitCheckUseCase()
    }
}

class StubRateLimitCheckUseCase : RateLimitCheckUseCase {
    var nextResult: RateLimitCheckResult? = null
    var nextException: Exception? = null

    override fun check(input: RateLimitCheckInput): RateLimitCheckResult {
        nextException?.let { throw it }
        return nextResult ?: error("테스트용 RateLimitCheckResult가 설정되지 않았습니다.")
    }

    fun reset() {
        nextResult = null
        nextException = null
    }
}
