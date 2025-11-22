package com.redguard.domain.limit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface LimitHitLogRepository : JpaRepository<LimitHitLog, Long> {
    fun findByTenantIdAndOccurredAtBetween(tenantId: Long, from: Instant, to: Instant): List<LimitHitLog>
}
