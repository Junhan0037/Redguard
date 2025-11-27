package com.redguard.api.admin.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank
    @field:Size(min = 4, max = 100)
    val loginId: String,

    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val password: String
)
