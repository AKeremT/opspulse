package com.opspulse.auth.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed JWT configuration bound from {@code app.jwt.*}.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT secret must not be blank")
        String secret,

        @Positive(message = "JWT access token expiration must be positive")
        long accessExpirationMs,

        @Positive(message = "JWT refresh token expiration must be positive")
        long refreshExpirationMs
) {
}
