package com.redguard.domain.limit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface LimitHitLogRepository : JpaRepository<LimitHitLog, Long> {
    fun findByTenantIdAndOccurredAtBetween(tenantId: Long, from: Instant, to: Instant): List<LimitHitLog>

    @Query(
        """
        select l
        from LimitHitLog l
        where l.tenant.id = :tenantId
          and l.occurredAt >= :from
          and l.occurredAt <= :to
          and (:reason is null or l.reason = :reason)
          and (:userId is null or l.userId = :userId)
          and (:apiPath is null or l.apiPath = :apiPath)
    """
    )
    fun search(
        @Param("tenantId") tenantId: Long,
        @Param("reason") reason: LimitHitReason?,
        @Param("userId") userId: String?,
        @Param("apiPath") apiPath: String?,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable
    ): Page<LimitHitLog>
}
