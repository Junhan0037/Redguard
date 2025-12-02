package com.redguard.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.nothingFor
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/**
 * `/internal/rate-limit/check`를 대상으로 하는 Gatling 기반 부하 테스트 시뮬레이션
 * - 스코프별(테넌트/테넌트+유저/테넌트+API) 요청 패턴을 분리해 시나리오 단위로 주입
 * - 시스템 프로퍼티로 RPS/지속 시간/타겟 테넌트 식별자를 덮어쓸 수 있어 재사용성을 확보
 */
class RateLimitSimulation : io.gatling.javaapi.core.Simulation() {

    // 기본 접속 정보 및 부하 프로파일(필요 시 -D 옵션으로 덮어씀)
    private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
    private val steadyRps = System.getProperty("steadyRps")?.toDouble() ?: 250.0
    private val steadyDurationSeconds = System.getProperty("steadyDurationSeconds")?.toLong() ?: 600L
    private val steadyRampSeconds = System.getProperty("steadyRampSeconds")?.toLong() ?: 120L
    private val spikeRps = System.getProperty("spikeRps")?.toDouble() ?: 800.0
    private val spikeDurationSeconds = System.getProperty("spikeDurationSeconds")?.toLong() ?: 120L
    private val spikeRampSeconds = System.getProperty("spikeRampSeconds")?.toLong() ?: 30L
    private val spikeDelaySeconds = System.getProperty("spikeDelaySeconds")?.toLong() ?: 180L
    private val quotaRps = System.getProperty("quotaRps")?.toDouble() ?: 40.0
    private val quotaDurationSeconds = System.getProperty("quotaDurationSeconds")?.toLong() ?: 900L
    private val quotaDelaySeconds = System.getProperty("quotaDelaySeconds")?.toLong() ?: 30L

    private val httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .userAgentHeader("redguard-gatling/1.0")
        .disableWarmUp()

    /**
     * 부하 대상 페이로드 구성을 위한 파라미터 묶음
     */
    private data class RateLimitTarget(
        val scope: String,
        val tenantId: String,
        val apiPath: String?,
        val httpMethod: String,
        val userId: String? = null,
        val increment: Int = 1
    )

    // 플랜/테넌트 오버라이드를 쉽게 하기 위해 System Property를 활용
    private val tenantFree = System.getProperty("tenantFreeId", "tenant-free")
    private val tenantPro = System.getProperty("tenantProId", "tenant-pro")
    private val tenantEnterprise = System.getProperty("tenantEnterpriseId", "tenant-enterprise")

    // TENANT_API 스코프: 엔드포인트 별 부하를 재현
    private val tenantApiTargets = listOf(
        RateLimitTarget(scope = "TENANT_API", tenantId = tenantFree, apiPath = "/v1/report/export", httpMethod = "POST"),
        RateLimitTarget(scope = "TENANT_API", tenantId = tenantPro, apiPath = "/v1/report/search", httpMethod = "GET"),
        RateLimitTarget(scope = "TENANT_API", tenantId = tenantEnterprise, apiPath = "/v1/report/detail/*", httpMethod = "GET")
    )

    // TENANT_USER 스코프: 사용자 단위 한도를 검증
    private val tenantUserTargets = listOf(
        RateLimitTarget(scope = "TENANT_USER", tenantId = tenantPro, apiPath = null, httpMethod = "GET", userId = "member-1"),
        RateLimitTarget(scope = "TENANT_USER", tenantId = tenantPro, apiPath = null, httpMethod = "GET", userId = "member-2"),
        RateLimitTarget(scope = "TENANT_USER", tenantId = tenantEnterprise, apiPath = null, httpMethod = "POST", userId = "analyst-1")
    )

    // TENANT 스코프: 장기 누적 쿼터(일/월) 소비를 재현
    private val tenantTargets = listOf(
        RateLimitTarget(scope = "TENANT", tenantId = tenantFree, apiPath = null, httpMethod = "GET"),
        RateLimitTarget(scope = "TENANT", tenantId = tenantEnterprise, apiPath = null, httpMethod = "POST")
    )

    /**
     * 대상 리스트에서 랜덤으로 요청 파라미터를 선택
     */
    private fun selectTarget(targets: List<RateLimitTarget>): RateLimitTarget {
        return targets[ThreadLocalRandom.current().nextInt(targets.size)]
    }

    /**
     * RateLimitCheckRequest JSON을 문자열로 생성
     * - 불필요한 의존성 없이 문자열 빌더로 구성해 GC/직렬화 오버헤드를 줄임
     */
    private fun buildPayload(target: RateLimitTarget): String {
        val builder = StringBuilder(200)
        builder.append("{\"scope\":\"").append(target.scope).append("\",")
        builder.append("\"tenantId\":\"").append(target.tenantId).append("\",")
        target.userId?.let { builder.append("\"userId\":\"").append(it).append("\",") }
        target.apiPath?.let { builder.append("\"apiPath\":\"").append(it).append("\",") }
        builder.append("\"httpMethod\":\"").append(target.httpMethod).append("\",")
        builder.append("\"timestamp\":\"").append(Instant.now().toString()).append("\",")
        builder.append("\"increment\":").append(target.increment)
        builder.append("}")
        return builder.toString()
    }

    /**
     * 공통 HTTP 체인을 생성해 상태코드(200/403/429/503) 허용 여부와 페이로드 생성 로직을 캡슐화
     */
    private fun rateLimitRequest(name: String, targets: List<RateLimitTarget>): ChainBuilder =
        io.gatling.javaapi.core.CoreDsl.exec { session ->
            val target = selectTarget(targets)
            session.set("payload", buildPayload(target))
        }.exec(
            http(name)
                .post("/internal/rate-limit/check")
                .body(io.gatling.javaapi.http.HttpDsl.StringBody { session -> session.getString("payload") })
                .check(status().`in`(200, 403, 429, 503))
        )

    // 시나리오 1: 정상 트래픽(Plan 기본값) - 점진 Ramp 후 일정 RPS 유지
    private val steadyScenario = scenario("tenant-api-steady")
        .exec(rateLimitRequest("rate-limit-api", tenantApiTargets))

    // 시나리오 2: 짧은 버스트로 초/분당 한도 초과 상황 재현
    private val spikeScenario = scenario("tenant-user-spike")
        .exec(rateLimitRequest("rate-limit-user", tenantUserTargets))

    // 시나리오 3: 일/월 쿼터 소비 패턴을 모사하는 장기 트래픽
    private val quotaScenario = scenario("tenant-quota-drift")
        .exec(rateLimitRequest("rate-limit-tenant", tenantTargets))

    init {
        setUp(
            steadyScenario.injectOpen(
                rampUsersPerSec(10.0).to(steadyRps).during(Duration.ofSeconds(steadyRampSeconds)),
                constantUsersPerSec(steadyRps).during(Duration.ofSeconds(steadyDurationSeconds))
            ),
            spikeScenario.injectOpen(
                nothingFor(Duration.ofSeconds(spikeDelaySeconds)),
                rampUsersPerSec(1.0).to(spikeRps).during(Duration.ofSeconds(spikeRampSeconds)),
                constantUsersPerSec(spikeRps).during(Duration.ofSeconds(spikeDurationSeconds))
            ),
            quotaScenario.injectOpen(
                nothingFor(Duration.ofSeconds(quotaDelaySeconds)),
                constantUsersPerSec(quotaRps).during(Duration.ofSeconds(quotaDurationSeconds))
            )
        ).protocols(httpProtocol)
            .assertions(
                // 97% 이상 성공, 95% 지연은 80ms 이하를 기본 목표로 설정
                global().successfulRequests().percent().gt(97.0),
                global().responseTime().percentile3().lte(80),
                global().failedRequests().percent().lt(2.0)
            )
    }
}
