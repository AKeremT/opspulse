package com.opspulse.monitor.dto;

import com.opspulse.monitor.domain.MonitorStatus;
import java.time.Instant;
import java.util.UUID;

public record MonitorCheckResponse(
        UUID id,
        UUID monitorId,
        MonitorStatus status,
        Integer statusCode,
        Integer latencyMs,
        String errorMessage,
        Instant checkedAt
) {
}
