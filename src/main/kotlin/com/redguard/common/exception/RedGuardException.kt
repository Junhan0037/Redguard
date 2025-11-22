package com.redguard.common.exception

open class RedGuardException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
    cause: Throwable? = null
) : RuntimeException(message, cause)
