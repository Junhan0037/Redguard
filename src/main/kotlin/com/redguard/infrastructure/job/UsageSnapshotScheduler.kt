package com.redguard.infrastructure.job

import com.redguard.application.reporting.UsageSnapshotIngestionService
import com.redguard.infrastructure.config.UsageSnapshotJobProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.UUID
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Redis 쿼터 카운터를 주기적으로 DB 스냅샷으로 적재하는 스케줄러
 * - Redis 락으로 단일 인스턴스만 실행되도록 보장
 */
@Component
class UsageSnapshotScheduler(
    private val usageSnapshotIngestionService: UsageSnapshotIngestionService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val jobProperties: UsageSnapshotJobProperties
) {

    private val logger = KotlinLogging.logger {}
    private val lockReleaseScript = DefaultRedisScript(LOCK_RELEASE_LUA, Long::class.java)

    /**
     * 주기적으로 Redis 카운터를 DB 스냅샷으로 반영
     */
    @Scheduled(cron = "\${redguard.usage-snapshot.cron:0 */5 * * * *}", zone = "UTC")
    fun snapshot() {
        if (!jobProperties.enabled) {
            logger.debug { "UsageSnapshot 스케줄러가 비활성화되어 실행을 건너뜁니다." }
            return
        }

        // 멀티 인스턴스 환경에서 중복 실행을 피하기 위해 Redis 분산락 사용
        val lockToken = acquireLock() ?: return
        try {
            val result = usageSnapshotIngestionService.ingest()
            logger.info {
                "UsageSnapshot 적재 완료: scanned=${result.scannedKeys}, ingested=${result.ingestedCounters}, inserted=${result.inserted}, updated=${result.updated}, skipped=${result.skipped}"
            }
        } catch (ex: Exception) {
            logger.error(ex) { "UsageSnapshot 스케줄 실행 중 오류가 발생했습니다." }
        } finally {
            releaseLock(lockToken)
        }
    }

    /**
     * 분산락을 획득해 단일 인스턴스만 스케줄을 실행하도록 보장
     */
    private fun acquireLock(): String? {
        val token = UUID.randomUUID().toString()
        return try {
            val acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(jobProperties.lockKey, token, Duration.ofSeconds(jobProperties.lockTtlSeconds))
            if (acquired == true) {
                token
            } else {
                logger.debug { "UsageSnapshot 스케줄러 락을 획득하지 못했습니다. 다른 인스턴스가 실행 중일 수 있습니다." }
                null
            }
        } catch (ex: Exception) {
            logger.error(ex) { "UsageSnapshot 스케줄러 락 획득 중 Redis 오류가 발생했습니다." }
            null
        }
    }

    /**
     * 락 소유자 토큰이 일치하는 경우에만 락을 해제
     */
    private fun releaseLock(token: String) {
        try {
            // 락 소유자 토큰이 일치할 때만 해제
            stringRedisTemplate.execute(lockReleaseScript, listOf(jobProperties.lockKey), token)
        } catch (ex: Exception) {
            logger.warn(ex) { "UsageSnapshot 스케줄러 락 해제 중 오류가 발생했습니다." }
        }
    }

    companion object {
        private const val LOCK_RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
