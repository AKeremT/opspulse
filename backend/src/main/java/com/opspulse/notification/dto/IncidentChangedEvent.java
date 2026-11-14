package com.opspulse.notification.dto;

import com.opspulse.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record IncidentChangedEvent(
        UUID incidentId,
        UUID monitorId,
        UUID workspaceId,
        String monitorName,
        IncidentStatus status,
        String summary,
        Instant startedAt,
        Instant acknowledgedAt,
        Instant resolvedAt
) {
}
