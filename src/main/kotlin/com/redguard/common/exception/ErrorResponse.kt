package com.redguard.common.exception

import com.redguard.common.tracing.TraceIdContext
import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<FieldErrorDetail> = emptyList(),
    val traceId: String? = TraceIdContext.currentTraceId(),
    val timestamp: Instant = Instant.now()
) {
    data class FieldErrorDetail(
        val field: String?,
        val reason: String
    )
}
