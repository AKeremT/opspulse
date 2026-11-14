package com.opspulse.maintenance.dto;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceWindowResponse(
        UUID id,
        UUID workspaceId,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        Instant createdAt
) {
}
