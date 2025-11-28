package com.redguard.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "redguard.usage-snapshot")
data class UsageSnapshotJobProperties(
    // 스케줄러 온오프 플래그
    val enabled: Boolean = true,
    // 실행 주기(CRON, UTC)
    val cron: String = "0 */5 * * * *",
    // 멀티 인스턴스 동시 실행 방지를 위한 락 키
    val lockKey: String = "job:usage-snapshot:lock",
    // 락 TTL(초), 지나치게 짧으면 중복 실행될 수 있다
    val lockTtlSeconds: Long = 300,
    // Redis SCAN count 힌트
    val scanCount: Long = 1000,
    // MGET 청크 크기
    val fetchBatchSize: Int = 200
)
