package com.redguard.api.config

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.ErrorResponse
import com.redguard.common.exception.RedGuardException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(RedGuardException::class)
    fun handleRedGuardException(ex: RedGuardException): ResponseEntity<ErrorResponse> {
        logger.warn(ex) { "RedGuardException occurred" }
        return buildResponse(ex.errorCode, ex.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class, ConstraintViolationException::class, HttpMessageNotReadableException::class)
    fun handleValidationExceptions(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.debug(ex) { "Validation exception raised" }
        val details = when (ex) {
            is MethodArgumentNotValidException -> ex.bindingResult.fieldErrors.map {
                ErrorResponse.FieldErrorDetail(it.field, it.defaultMessage ?: "유효하지 않은 값입니다.")
            }
            is BindException -> ex.bindingResult.fieldErrors.map {
                ErrorResponse.FieldErrorDetail(it.field, it.defaultMessage ?: "유효하지 않은 값입니다.")
            }
            is ConstraintViolationException -> ex.constraintViolations.map {
                ErrorResponse.FieldErrorDetail(it.propertyPath.toString(), it.message)
            }
            is HttpMessageNotReadableException -> listOf(ErrorResponse.FieldErrorDetail(null, "요청 본문을 읽을 수 없습니다."))
            else -> emptyList()
        }
        return buildResponse(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage, details)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unexpected exception occurred" }
        return buildResponse(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage)
    }

    private fun buildResponse(
        errorCode: ErrorCode,
        message: String,
        details: List<ErrorResponse.FieldErrorDetail> = emptyList()
    ): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            code = errorCode.name,
            message = message,
            details = details
        )
        return ResponseEntity(errorResponse, HttpHeaders.EMPTY, errorCode.httpStatus)
    }
}
