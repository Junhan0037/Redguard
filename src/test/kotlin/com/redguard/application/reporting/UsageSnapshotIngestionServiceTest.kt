package com.redguard.application.reporting

import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.domain.ratelimit.RedisKeyDimensions
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import com.redguard.domain.usage.UsageSnapshotPeriod
import com.redguard.domain.usage.UsageSnapshotRepository
import com.redguard.infrastructure.redis.RedisKeyBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Redis 쿼터 키 → UsageSnapshot 적재 경로를 통합 검증
 * - 실제 Redis(Testcontainers)와 JPA를 사용해 upsert 동작을 확인
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UsageSnapshotIngestionServiceTest @Autowired constructor(
    private val usageSnapshotIngestionService: UsageSnapshotIngestionService,
    private val usageSnapshotRepository: UsageSnapshotRepository,
    private val tenantRepository: TenantRepository,
    private val planRepository: PlanRepository,
    private val stringRedisTemplate: StringRedisTemplate,
    private val redisKeyBuilder: RedisKeyBuilder
) {

    @BeforeEach
    fun setUp() {
        usageSnapshotRepository.deleteAll()
        tenantRepository.deleteAll()
        planRepository.deleteAll()
        flushRedis()
    }

    /**
     * SCAN+MGET을 통해 DAY/MONTH 버킷이 스냅샷으로 저장되는지 검증
     */
    @Test
    fun `Redis_쿼터_키를_스냅샷으로_적재한다`() {
        val tenant = createTenant("snapshot-tenant")
        val now = Instant.parse("2025-01-15T10:15:30Z")

        val tenantQuotaKey = redisKeyBuilder.quotaKey(
            dimensions = redisKeyDimensions(scope = RateLimitScope.TENANT, tenant = tenant.name),
            period = QuotaPeriod.DAILY,
            timestamp = now
        )
        val userQuotaKey = redisKeyBuilder.quotaKey(
            dimensions = redisKeyDimensions(scope = RateLimitScope.TENANT_USER, tenant = tenant.name, userId = "reporter"),
            period = QuotaPeriod.MONTHLY,
            timestamp = now
        )
        val apiQuotaKey = redisKeyBuilder.quotaKey(
            dimensions = redisKeyDimensions(scope = RateLimitScope.TENANT_API, tenant = tenant.name, apiPath = "/v1/report/export"),
            period = QuotaPeriod.DAILY,
            timestamp = now
        )

        stringRedisTemplate.opsForValue().set(tenantQuotaKey, "15")
        stringRedisTemplate.opsForValue().set(userQuotaKey, "120")
        stringRedisTemplate.opsForValue().set(apiQuotaKey, "33")

        val result = usageSnapshotIngestionService.ingest()
        val snapshots = usageSnapshotRepository.findAll()

        assertThat(result.inserted).isEqualTo(3)
        assertThat(result.updated).isZero()
        assertThat(result.skipped).isZero()
        assertThat(snapshots).hasSize(3)

        val tenantSnapshot = snapshots.first { it.periodType == UsageSnapshotPeriod.DAY && it.apiPath == null }
        assertThat(tenantSnapshot.userId).isNull()
        assertThat(tenantSnapshot.totalCount).isEqualTo(15)
        assertThat(tenantSnapshot.snapshotDate).isEqualTo(LocalDate.of(2025, 1, 15))

        val userSnapshot = snapshots.first { it.periodType == UsageSnapshotPeriod.MONTH }
        assertThat(userSnapshot.userId).isEqualTo("reporter")
        assertThat(userSnapshot.apiPath).isNull()
        assertThat(userSnapshot.totalCount).isEqualTo(120)
        assertThat(userSnapshot.snapshotDate).isEqualTo(YearMonth.of(2025, 1).atDay(1))

        val apiSnapshot = snapshots.first { it.apiPath == "/v1/report/export" }
        assertThat(apiSnapshot.periodType).isEqualTo(UsageSnapshotPeriod.DAY)
        assertThat(apiSnapshot.totalCount).isEqualTo(33)
    }

    @Test
    fun `동일_키가_증가하면_스냅샷을_업데이트한다`() {
        val tenant = createTenant("snapshot-tenant-2")
        val now = Instant.parse("2025-02-10T00:00:00Z")
        val tenantQuotaKey = redisKeyBuilder.quotaKey(
            dimensions = redisKeyDimensions(scope = RateLimitScope.TENANT, tenant = tenant.name),
            period = QuotaPeriod.DAILY,
            timestamp = now
        )

        stringRedisTemplate.opsForValue().set(tenantQuotaKey, "5")
        usageSnapshotIngestionService.ingest()

        stringRedisTemplate.opsForValue().set(tenantQuotaKey, "25")
        val result = usageSnapshotIngestionService.ingest()
        val snapshots = usageSnapshotRepository.findAll()

        assertThat(result.inserted).isEqualTo(0)
        assertThat(result.updated).isEqualTo(1)
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots.first().totalCount).isEqualTo(25)
    }

    private fun createTenant(name: String): Tenant {
        val plan = planRepository.save(
            Plan(
                name = "PLAN-${UUID.randomUUID()}",
                description = "snapshot-plan",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 600,
                rateLimitPerDay = 10_000,
                quotaPerDay = 5_000,
                quotaPerMonth = 100_000
            )
        )
        return tenantRepository.save(
            Tenant(
                name = name,
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )
    }

    /**
     * Redis 키 차원을 테스트 입력으로 생성
     */
    private fun redisKeyDimensions(scope: RateLimitScope, tenant: String, userId: String? = null, apiPath: String? = null) =
        RedisKeyDimensions(
            scope = scope,
            tenantId = tenant,
            userId = userId,
            apiPath = apiPath
        )

    /**
     * 테스트 Redis 초기화
     */
    private fun flushRedis() {
        stringRedisTemplate.execute { connection ->
            connection.serverCommands().flushAll()
            null
        }
    }

    companion object {
        init {
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
