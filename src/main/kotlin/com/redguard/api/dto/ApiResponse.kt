package com.redguard.api.dto

import com.redguard.common.tracing.TraceIdContext
import java.time.Instant

data class ApiResponse<T>(
    val success: Boolean = true,
    val data: T,
    val timestamp: Instant = Instant.now(),
    val traceId: String? = TraceIdContext.currentTraceId()
) {
    companion object {
        fun empty(): ApiResponse<String> = ApiResponse(data = "OK")
    }
}
