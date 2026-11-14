package com.opspulse.monitor.dto;

import com.opspulse.monitor.domain.MonitorHttpMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMonitorRequest(
        @NotBlank(message = "Monitor name is required")
        @Size(min = 2, max = 120, message = "Monitor name must be between 2 and 120 characters")
        String name,

        @NotBlank(message = "Target URL is required")
        @Size(max = 2048, message = "Target URL must not exceed 2048 characters")
        String url,

        MonitorHttpMethod httpMethod,

        @Min(value = 10, message = "Interval must be at least 10 seconds")
        @Max(value = 86400, message = "Interval must not exceed 86400 seconds")
        Integer intervalSeconds,

        @Min(value = 500, message = "Timeout must be at least 500 ms")
        @Max(value = 30000, message = "Timeout must not exceed 30000 ms")
        Integer timeoutMs,

        @Min(value = 100, message = "Expected status must be between 100 and 599")
        @Max(value = 599, message = "Expected status must be between 100 and 599")
        Integer expectedStatus,

        @Min(value = 1, message = "Failure threshold must be at least 1")
        @Max(value = 20, message = "Failure threshold must not exceed 20")
        Integer failureThreshold
) {
}
