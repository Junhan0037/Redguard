package com.redguard.infrastructure.security

import com.redguard.domain.admin.AdminRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

class JwtTokenProvider(
    private val properties: AdminAuthProperties
) {
    private val accessKey: SecretKey = Keys.hmacShaKeyFor(properties.accessTokenSecret.toByteArray(StandardCharsets.UTF_8))
    private val refreshKey: SecretKey = Keys.hmacShaKeyFor(properties.refreshTokenSecret.toByteArray(StandardCharsets.UTF_8))
    private val accessParser = Jwts.parserBuilder().setSigningKey(accessKey).setAllowedClockSkewSeconds(properties.allowedClockSkewSeconds).build()
    private val refreshParser = Jwts.parserBuilder().setSigningKey(refreshKey).setAllowedClockSkewSeconds(properties.allowedClockSkewSeconds).build()

    /**
     * 관리자용 Access Token을 발급
     */
    fun generateAccessToken(subject: String, roles: Set<AdminRole>): GeneratedToken {
        val now = Instant.now()
        val expiresAt = now.plus(properties.accessTokenTtl)
        val jti = UUID.randomUUID().toString()
        val token = Jwts.builder()
            .setId(jti)
            .setIssuer(properties.issuer)
            .setSubject(subject)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(expiresAt))
            .claim("tokenType", TokenType.ACCESS.name)
            .claim("roles", roles.map { it.name })
            .signWith(accessKey, SignatureAlgorithm.HS256)
            .compact()
        return GeneratedToken(token, expiresAt, jti, TokenType.ACCESS)
    }

    /**
     * 관리자용 Refresh Token을 발급
     */
    fun generateRefreshToken(subject: String): GeneratedToken {
        val now = Instant.now()
        val expiresAt = now.plus(properties.refreshTokenTtl)
        val jti = UUID.randomUUID().toString()
        val token = Jwts.builder()
            .setId(jti)
            .setIssuer(properties.issuer)
            .setSubject(subject)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(expiresAt))
            .claim("tokenType", TokenType.REFRESH.name)
            .signWith(refreshKey, SignatureAlgorithm.HS256)
            .compact()
        return GeneratedToken(token, expiresAt, jti, TokenType.REFRESH)
    }

    /**
     * Access Token을 파싱/검증하고 클레임을 도메인 모델로 변환
     */
    fun parseAccessToken(token: String): ParsedToken {
        val claims = accessParser.parseClaimsJws(token).body
        val tokenType = claims["tokenType"]?.toString()
        require(tokenType == TokenType.ACCESS.name) { "ACCESS 토큰이 아닙니다." }
        require(claims.issuer == properties.issuer) { "발급자를 확인할 수 없습니다." }
        val roles = (claims["roles"] as? Collection<*>)?.mapNotNull { value ->
            runCatching { AdminRole.valueOf(value.toString()) }.getOrNull()
        }?.toSet() ?: emptySet()
        return ParsedToken(
            subject = claims.subject,
            jti = claims.id ?: error("JTI가 누락되었습니다."),
            roles = roles,
            expiresAt = claims.expiration.toInstant(),
            tokenType = TokenType.ACCESS
        )
    }

    /**
     * Refresh Token을 파싱/검증하고 클레임을 도메인 모델로 변환
     */
    fun parseRefreshToken(token: String): ParsedToken {
        val claims = refreshParser.parseClaimsJws(token).body
        val tokenType = claims["tokenType"]?.toString()
        require(tokenType == TokenType.REFRESH.name) { "REFRESH 토큰이 아닙니다." }
        require(claims.issuer == properties.issuer) { "발급자를 확인할 수 없습니다." }
        return ParsedToken(
            subject = claims.subject,
            jti = claims.id ?: error("JTI가 누락되었습니다."),
            roles = emptySet(),
            expiresAt = claims.expiration.toInstant(),
            tokenType = TokenType.REFRESH
        )
    }
}

data class GeneratedToken(
    val value: String,
    val expiresAt: Instant,
    val jti: String,
    val tokenType: TokenType
)

data class ParsedToken(
    val subject: String,
    val jti: String,
    val roles: Set<AdminRole>,
    val expiresAt: Instant,
    val tokenType: TokenType
)

enum class TokenType {
    ACCESS,
    REFRESH
}
