package com.redguard.infrastructure.persistence.admin

import com.redguard.domain.admin.AdminRole
import com.redguard.domain.admin.AdminStatus
import com.redguard.domain.common.AuditableEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(
    name = "admin_user",
    indexes = [
        Index(name = "idx_admin_user_login_id", columnList = "login_id", unique = true),
        Index(name = "idx_admin_user_status", columnList = "status")
    ]
)
class AdminUserEntity(
    @Column(name = "login_id", nullable = false, length = 100, unique = true)
    val loginId: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "admin_user_roles", joinColumns = [JoinColumn(name = "admin_user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    var roles: MutableSet<AdminRole> = mutableSetOf(),

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: AdminStatus = AdminStatus.ACTIVE,

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int = 0,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null
) : AuditableEntity() {
    /**
     * 로그인 성공 시 상태/로그인 시각을 초기화하고 최신화
     */
    fun recordLoginSuccess(now: Instant) {
        failedAttempts = 0
        lockedUntil = null
        lastLoginAt = now
        updatedAt = now
    }

    /**
     * 로그인 실패 횟수를 증가시키고 필요 시 잠금 처리
     */
    fun recordLoginFailure(now: Instant, maxFailedAttempts: Int, lockoutDurationMinutes: Long) {
        failedAttempts += 1
        if (failedAttempts >= maxFailedAttempts) {
            status = AdminStatus.LOCKED
            lockedUntil = now.plus(lockoutDurationMinutes, ChronoUnit.MINUTES)
        }
        updatedAt = now
    }

    /**
     * 잠금 만료가 지났다면 계정을 복구
     */
    fun unlockIfExpired(now: Instant) {
        if (status == AdminStatus.LOCKED && lockedUntil?.isBefore(now) == true) {
            status = AdminStatus.ACTIVE
            failedAttempts = 0
            lockedUntil = null
        }
    }

    /**
     * 비밀번호를 변경하며 감사 시간 갱신
     */
    fun updatePassword(newHash: String, now: Instant) {
        passwordHash = newHash
        updatedAt = now
    }
}
