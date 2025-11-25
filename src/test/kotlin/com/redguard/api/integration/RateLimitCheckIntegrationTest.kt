package com.redguard.api.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.api.dto.ratelimit.RateLimitCheckRequest
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RateLimitCheckIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val planRepository: PlanRepository,
    private val tenantRepository: TenantRepository,
    private val apiPolicyRepository: ApiPolicyRepository,
    private val stringRedisTemplate: StringRedisTemplate
) {

    @BeforeEach
    fun setUp() {
        apiPolicyRepository.deleteAll()
        tenantRepository.deleteAll()
        planRepository.deleteAll()
        flushRedis()
    }

    @Test
    fun `실제 Redis와 정책으로 허용된 요청은 200 OK와 ALLOWED를 반환한다`() {
        val tenant = createTenantWithPlan(
            rateLimitPerSecond = 5,
            rateLimitPerMinute = 100,
            rateLimitPerDay = 1000
        )
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                request(tenant.name, "/v1/report", timestamp)
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.decision") { value("ALLOWED") }
            jsonPath("$.data.windows.second.limit") { value(5) }
            jsonPath("$.data.windows.second.effectiveCount") { value(1) }
            jsonPath("$.data.fallbackApplied") { value(false) }
        }
    }

    @Test
    fun `초당 한도를 초과하면 429와 RATE_LIMIT_EXCEEDED 사유를 반환한다`() {
        val tenant = createTenantWithPlan(
            rateLimitPerSecond = 2,
            rateLimitPerMinute = 120,
            rateLimitPerDay = 1000
        )
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val request = request(tenant.name, "/v1/report", timestamp)

        repeat(2) {
            mockMvc.post("/internal/rate-limit/check") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect { status { isOk() } }
        }

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.data.decision") { value("RATE_LIMIT_EXCEEDED") }
            jsonPath("$.data.windows.second.allowed") { value(false) }
            jsonPath("$.data.windows.second.effectiveCount") { value(3) }
        }
    }

    @Test
    fun `일일 쿼터를 초과하면 403과 QUOTA_EXCEEDED 사유를 반환한다`() {
        val tenant = createTenantWithPlan(
            rateLimitPerSecond = null,
            rateLimitPerMinute = null,
            rateLimitPerDay = null,
            quotaPerDay = 2
        )
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val request = request(tenant.name, "/v1/quota", timestamp)

        repeat(2) {
            mockMvc.post("/internal/rate-limit/check") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect { status { isOk() } }
        }

        mockMvc.post("/internal/rate-limit/check") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.data.decision") { value("QUOTA_EXCEEDED") }
            jsonPath("$.data.quotas.daily.totalCount") { value(3) }
        }
    }

    private fun request(tenantId: String, apiPath: String, timestamp: Instant): RateLimitCheckRequest {
        return RateLimitCheckRequest(
            scope = RateLimitScope.TENANT_API,
            tenantId = tenantId,
            userId = "user-${tenantId.take(8)}",
            apiPath = apiPath,
            httpMethod = ApiHttpMethod.GET,
            timestamp = timestamp,
            increment = 1
        )
    }

    private fun createTenantWithPlan(
        rateLimitPerSecond: Long?,
        rateLimitPerMinute: Long? = null,
        rateLimitPerDay: Long? = null,
        quotaPerDay: Long? = null,
        quotaPerMonth: Long? = null
    ): Tenant {
        val plan = planRepository.save(
            Plan(
                name = "PLAN-${UUID.randomUUID()}",
                description = "integration-test plan",
                rateLimitPerSecond = rateLimitPerSecond,
                rateLimitPerMinute = rateLimitPerMinute,
                rateLimitPerDay = rateLimitPerDay,
                quotaPerDay = quotaPerDay,
                quotaPerMonth = quotaPerMonth
            )
        )

        return tenantRepository.save(
            Tenant(
                name = "tenant-${UUID.randomUUID()}",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )
    }

    private fun flushRedis() {
        stringRedisTemplate.execute { connection ->
            connection.serverCommands().flushAll()
            null
        }
    }

    companion object {
        init {
            // Docker 데몬 API 버전 호환성 문제를 피하기 위해 Testcontainers가 사용할 버전을 명시
            System.setProperty("DOCKER_API_VERSION", "1.41")
        }

        @Container
        @JvmStatic
        private val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7.4.1-alpine"))
            .apply { withExposedPorts(6379) }

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
