package dev.rymarovych.event_analytics.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.http.ProblemDetail;

/**
 * Shared API error documentation for responses produced by ApiExceptionHandler and security.
 * Generic 500 responses are omitted because they add no endpoint-specific guidance.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(
    responseCode = "400",
    description = "Malformed request; `errors` names the fields objected to",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
    responseCode = "401",
    description = "The bearer token is missing, malformed, expired, or signed by an unknown key",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
@interface ProblemResponses {}
