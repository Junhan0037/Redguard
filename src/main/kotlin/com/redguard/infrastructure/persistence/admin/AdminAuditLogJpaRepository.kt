package com.redguard.infrastructure.persistence.admin

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AdminAuditLogJpaRepository : JpaRepository<AdminAuditLogEntity, Long>
