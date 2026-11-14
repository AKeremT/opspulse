package com.opspulse.maintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateMaintenanceWindowRequest(
        @NotBlank(message = "Maintenance window title is required")
        @Size(min = 2, max = 160, message = "Title must be between 2 and 160 characters")
        String title,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @NotNull(message = "Start time is required")
        Instant startsAt,

        @NotNull(message = "End time is required")
        Instant endsAt
) {
}
