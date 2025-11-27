package com.redguard.api.admin.auth

import com.redguard.api.admin.auth.dto.LoginRequest
import com.redguard.api.admin.auth.dto.LogoutRequest
import com.redguard.api.admin.auth.dto.RefreshRequest
import com.redguard.api.admin.auth.dto.TokenResponse
import com.redguard.application.admin.AdminAuthService
import com.redguard.infrastructure.security.AdminPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/auth")
class AdminAuthController(
    private val adminAuthService: AdminAuthService
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpServletRequest: HttpServletRequest
    ): TokenResponse {
        val tokens = adminAuthService.login(request.loginId, request.password, clientIp(httpServletRequest), httpServletRequest.getHeader("User-Agent"))
        return TokenResponse.from(tokens)
    }

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
        httpServletRequest: HttpServletRequest
    ): TokenResponse {
        val tokens = adminAuthService.refresh(request.refreshToken, clientIp(httpServletRequest), httpServletRequest.getHeader("User-Agent"))
        return TokenResponse.from(tokens)
    }

    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
        @AuthenticationPrincipal principal: AdminPrincipal,
        httpServletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val accessToken = resolveAccessToken(httpServletRequest)
        adminAuthService.logout(
            accessToken = accessToken,
            refreshToken = request.refreshToken,
            principal = principal,
            clientIp = clientIp(httpServletRequest),
            userAgent = httpServletRequest.getHeader("User-Agent")
        )
        return ResponseEntity.noContent().build()
    }

    private fun resolveAccessToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) {
            return null
        }
        return header.substringAfter("Bearer ").trim().ifEmpty { null }
    }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
}
