package com.opspulse.monitor.dto;

import com.opspulse.monitor.domain.MonitorHttpMethod;
import com.opspulse.monitor.domain.MonitorStatus;
import java.time.Instant;
import java.util.UUID;

public record MonitorResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String url,
        MonitorHttpMethod httpMethod,
        int intervalSeconds,
        int timeoutMs,
        int expectedStatus,
        int failureThreshold,
        int consecutiveFailures,
        MonitorStatus currentStatus,
        boolean paused,
        Instant nextCheckAt,
        Instant lastCheckedAt,
        Integer lastLatencyMs,
        Instant createdAt
) {
}
