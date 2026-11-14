package com.opspulse.incident.dto;

import com.opspulse.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID monitorId,
        String monitorName,
        UUID workspaceId,
        IncidentStatus status,
        Instant startedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        String summary
) {
}
