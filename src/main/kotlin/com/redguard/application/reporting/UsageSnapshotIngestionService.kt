package com.redguard.application.reporting

import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.usage.UsageSnapshot
import com.redguard.domain.usage.UsageSnapshotPeriod
import com.redguard.domain.usage.UsageSnapshotRepository
import com.redguard.infrastructure.config.UsageSnapshotJobProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Redis에 누적된 쿼터 카운터를 UsageSnapshot 엔티티로 적재하는 배치 유스케이스
 * - SCAN + MGET으로 활성 쿼터 키를 읽어와 테넌트/유저/API/기간별 스냅샷을 upsert
 * - 동일 기간 스냅샷을 한 트랜잭션에서 처리해 중복/결측을 방지
 */
@Service
class UsageSnapshotIngestionService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val tenantRepository: TenantRepository,
    private val usageSnapshotRepository: UsageSnapshotRepository,
    private val jobProperties: UsageSnapshotJobProperties
) {

    private val logger = KotlinLogging.logger {}
    private val keySerializer = stringRedisTemplate.stringSerializer

    /**
     * Redis 카운터를 읽어 DB 스냅샷으로 동기화
     * - 테넌트별로 기존 스냅샷을 조회해 변경분만 삽입/갱신
     */
    @Transactional
    fun ingest(): UsageSnapshotIngestionResult {
        val readResult = readQuotaCounters()
        if (readResult.counters.isEmpty()) {
            return UsageSnapshotIngestionResult(
                scannedKeys = readResult.scannedKeys,
                ingestedCounters = 0,
                inserted = 0,
                updated = 0,
                skipped = readResult.skippedKeys
            )
        }

        val tenantNames = readResult.counters.map { it.tenantName }.toSet()
        val tenants = tenantRepository.findAllByNameIn(tenantNames)
        val tenantMap = tenants.associateBy { it.name }

        var skipped = readResult.skippedKeys
        var updated = 0
        val newSnapshots = mutableListOf<UsageSnapshot>()

        readResult.counters.groupBy { it.tenantName }.forEach { (tenantName, counters) ->
            val tenant = tenantMap[tenantName]
            if (tenant?.id == null) {
                skipped += counters.size
                logger.warn { "UsageSnapshot 적재를 건너뜁니다. 테넌트를 찾을 수 없습니다. tenant=$tenantName" }
                return@forEach
            }

            val startDate = counters.minOf { it.snapshotDate }
            val endDate = counters.maxOf { it.snapshotDate }

            // 조회 구간을 좁혀 동일 테넌트의 기존 스냅샷만 로딩해 upsert 비용 최소화
            val existing = usageSnapshotRepository.findByTenantIdAndSnapshotDateBetween(tenant.id!!, startDate, endDate)
            val existingMap = existing.associateBy {
                SnapshotKey(it.userId, it.apiPath, it.snapshotDate, it.periodType)
            }.toMutableMap()

            counters.forEach { counter ->
                val key = SnapshotKey(counter.userId, counter.apiPath, counter.snapshotDate, counter.periodType)
                val current = existingMap[key]
                if (current == null) {
                    val snapshot = UsageSnapshot(
                        tenant = tenant,
                        userId = counter.userId,
                        apiPath = counter.apiPath,
                        snapshotDate = counter.snapshotDate,
                        periodType = counter.periodType,
                        totalCount = counter.totalCount
                    )
                    newSnapshots.add(snapshot)
                    existingMap[key] = snapshot
                } else if (current.totalCount != counter.totalCount) {
                    current.totalCount = counter.totalCount
                    updated++
                }
            }
        }

        if (newSnapshots.isNotEmpty()) {
            usageSnapshotRepository.saveAll(newSnapshots)
        }

        return UsageSnapshotIngestionResult(
            scannedKeys = readResult.scannedKeys,
            ingestedCounters = readResult.counters.size,
            inserted = newSnapshots.size,
            updated = updated,
            skipped = skipped
        )
    }

    /**
     * Redis에서 쿼터 키를 스캔하고 값과 함께 파싱해 반환
     */
    private fun readQuotaCounters(): CounterReadResult {
        val scanOptions = ScanOptions.scanOptions()
            .match("$QUOTA_KEY_PREFIX:*")
            .count(jobProperties.scanCount.coerceAtLeast(1))
            .build()

        val keys = stringRedisTemplate.execute { connection ->
            // 키 공간 전체를 점진적으로 순회하기 위해 SCAN 사용
            connection.keyCommands().scan(scanOptions).use { cursor ->
                buildList {
                    while (cursor.hasNext()) {
                        val rawKey = cursor.next()
                        keySerializer.deserialize(rawKey)?.let { add(it) }
                    }
                }
            }
        } ?: emptyList()

        if (keys.isEmpty()) {
            return CounterReadResult(scannedKeys = keys.size, counters = emptyList(), skippedKeys = 0)
        }

        val valueOps = stringRedisTemplate.opsForValue()
        val counters = mutableListOf<QuotaCounter>()
        var skipped = 0
        val batchSize = jobProperties.fetchBatchSize.coerceAtLeast(1)

        // 대량 키를 한 번에 가져오지 않도록 MGET 청크 처리
        keys.chunked(batchSize).forEach { batch ->
            val values = valueOps.multiGet(batch) ?: emptyList()
            batch.forEachIndexed { index, key ->
                val rawValue = values.getOrNull(index)
                if (rawValue == null) {
                    skipped++
                    return@forEachIndexed
                }
                val counter = parseQuotaKey(key, rawValue)
                if (counter != null) {
                    counters.add(counter)
                } else {
                    skipped++
                }
            }
        }

        return CounterReadResult(
            scannedKeys = keys.size,
            counters = counters,
            skippedKeys = skipped
        )
    }

    /**
     * qt:{scope}:{tenantId}:{userId}:{apiPath}:{period} 형태의 키와 값을 UsageSnapshot 저장용 모델로 파싱
     */
    private fun parseQuotaKey(key: String, rawValue: String): QuotaCounter? {
        val segments = key.split(":")
        if (segments.size != 6 || segments[0] != QUOTA_KEY_PREFIX) {
            logger.debug { "스냅샷 적재 대상이 아닌 키를 무시합니다. key=$key" }
            return null
        }
        val scopeCode = segments[1]
        if (!SUPPORTED_SCOPES.contains(scopeCode)) {
            logger.warn { "알 수 없는 스코프의 쿼터 키를 무시합니다. key=$key" }
            return null
        }

        val tenantName = segments[2]
        val userId = segments[3].takeUnless { it == NOT_APPLICABLE_PLACEHOLDER }
        val apiPath = segments[4].takeUnless { it == API_WILDCARD_PLACEHOLDER }

        val bucket = segments[5]
        val (periodType, snapshotDate) = when (bucket.length) {
            DAILY_BUCKET_LENGTH -> UsageSnapshotPeriod.DAY to runCatching { parseDate(bucket) }.getOrNull()
            MONTHLY_BUCKET_LENGTH -> UsageSnapshotPeriod.MONTH to runCatching { parseMonth(bucket) }.getOrNull()
            else -> null
        } ?: return null
        if (snapshotDate == null) {
            logger.warn { "쿼터 키 버킷을 파싱하지 못해 스냅샷을 건너뜁니다. key=$key" }
            return null
        }

        val totalCount = rawValue.toLongOrNull()
        if (totalCount == null || totalCount < 0) {
            logger.warn { "쿼터 키 값이 숫자가 아니거나 음수입니다. key=$key value=$rawValue" }
            return null
        }

        return QuotaCounter(
            tenantName = tenantName,
            userId = userId,
            apiPath = apiPath,
            snapshotDate = snapshotDate,
            periodType = periodType,
            totalCount = totalCount
        )
    }

    private fun parseDate(bucket: String): LocalDate = LocalDate.parse(bucket, DAILY_FORMATTER)

    private fun parseMonth(bucket: String): LocalDate = YearMonth.parse(bucket, MONTHLY_FORMATTER).atDay(1)

    private data class QuotaCounter(
        val tenantName: String,
        val userId: String?,
        val apiPath: String?,
        val snapshotDate: LocalDate,
        val periodType: UsageSnapshotPeriod,
        val totalCount: Long
    )

    private data class CounterReadResult(
        val scannedKeys: Int,
        val counters: List<QuotaCounter>,
        val skippedKeys: Int
    )

    private data class SnapshotKey(
        val userId: String?,
        val apiPath: String?,
        val snapshotDate: LocalDate,
        val periodType: UsageSnapshotPeriod
    )

    companion object {
        private const val QUOTA_KEY_PREFIX = "qt"
        private const val NOT_APPLICABLE_PLACEHOLDER = "-"
        private const val API_WILDCARD_PLACEHOLDER = "*"
        private const val DAILY_BUCKET_LENGTH = 8
        private const val MONTHLY_BUCKET_LENGTH = 6
        private val DAILY_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val MONTHLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        private val SUPPORTED_SCOPES = setOf("tenant", "tenant_user", "tenant_api")
    }
}

data class UsageSnapshotIngestionResult(
    val scannedKeys: Int,
    val ingestedCounters: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: Int
)
