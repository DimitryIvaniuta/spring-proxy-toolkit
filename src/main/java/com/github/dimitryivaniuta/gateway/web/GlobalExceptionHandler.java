package com.github.dimitryivaniuta.gateway.web;

import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static com.github.dimitryivaniuta.gateway.web.RequestContextKeys.CORRELATION_ID_MDC_KEY;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path,
            String correlationId
    ) {}

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.set("Retry-After", String.valueOf(Math.max(0, ex.getRetryAfterSeconds()))); // seconds per RFC
        ApiError body = error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
        return new ResponseEntity<>(body, h, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleRse(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(error(status, ex.getReason(), req));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(HttpStatus.BAD_REQUEST, "Validation failed", req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(HttpStatus.NOT_FOUND, ex.getMessage(), request));
    }

    private ApiError error(HttpStatus status, String message, HttpServletRequest req) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                (message == null || message.isBlank()) ? status.getReasonPhrase() : message,
                req.getRequestURI(),
                MDC.get(CORRELATION_ID_MDC_KEY)
        );
    }


}
