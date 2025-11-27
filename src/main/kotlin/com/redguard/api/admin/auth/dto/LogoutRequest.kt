package com.redguard.api.admin.auth.dto

data class LogoutRequest(
    val refreshToken: String?
)
