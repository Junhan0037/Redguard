package com.redguard.api.dto

import com.redguard.api.dto.ratelimit.RateLimitCheckRequest
import com.redguard.domain.ratelimit.RateLimitScope
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Instant

class RateLimitCheckRequestValidationTest {

    @Test
    fun `스코프가_API를_요구하면_apiPath가_필수다`() {
        val request = RateLimitCheckRequest(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-1",
            userId = null,
            apiPath = null,
            httpMethod = HttpMethod.GET,
            timestamp = Instant.now()
        )

        val violations = validator.validate(request)

        assertTrue(violations.any { it.propertyPath.toString() == "apiPath" })
    }

    @Test
    fun `스코프가_user를_요구하면_userId가_필수다`() {
        val request = RateLimitCheckRequest(
            scope = RateLimitScope.TENANT_USER,
            tenantId = "tenant-1",
            userId = null,
            apiPath = "/v1/resource",
            httpMethod = HttpMethod.POST,
            timestamp = Instant.now()
        )

        val violations = validator.validate(request)

        assertTrue(violations.any { it.propertyPath.toString() == "userId" })
    }

    @Test
    fun `미래_timestamp는_거부된다`() {
        val request = RateLimitCheckRequest(
            scope = RateLimitScope.TENANT,
            tenantId = "tenant-1",
            userId = null,
            apiPath = "/v1/resource",
            httpMethod = HttpMethod.POST,
            timestamp = Instant.now().plusSeconds(60)
        )

        val violations = validator.validate(request)

        assertTrue(violations.any { it.propertyPath.toString() == "timestamp" })
    }

    @Test
    fun `정상_입력은_위반이_없다`() {
        val request = RateLimitCheckRequest(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-1",
            userId = "user-1",
            apiPath = "/v1/report",
            httpMethod = HttpMethod.POST,
            timestamp = Instant.now()
        )

        val violations = validator.validate(request)

        assertEquals(0, violations.size)
    }

    companion object {
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setup() {
            validator = Validation.buildDefaultValidatorFactory().validator
        }
    }
}
