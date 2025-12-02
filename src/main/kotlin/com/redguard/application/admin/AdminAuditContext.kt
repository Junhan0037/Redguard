package com.redguard.application.admin

/**
 * 관리자 행위에 대한 감사 컨텍스트 정보
 * - Web 계층에서 내려주는 사용자/접속 정보(IP, User-Agent)를 포함해 감사 로그 및 구조화 로그에 활용
 */
data class AdminAuditContext(
    val actorId: Long?,
    val ip: String?,
    val userAgent: String?
)
