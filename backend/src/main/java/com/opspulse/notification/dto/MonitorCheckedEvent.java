package com.opspulse.notification.dto;

import com.opspulse.monitor.domain.MonitorStatus;
import java.time.Instant;
import java.util.UUID;

public record MonitorCheckedEvent(
        UUID monitorId,
        UUID workspaceId,
        String monitorName,
        MonitorStatus status,
        Integer statusCode,
        int latencyMs,
        int consecutiveFailures,
        Instant checkedAt
) {
}
