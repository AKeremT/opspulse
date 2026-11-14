package com.opspulse.statuspage.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publicly visible status page payload. Intentionally omits sensitive monitor URLs,
 * alert channels, and user contact details.
 */
public record PublicStatusResponse(
        String slug,
        String title,
        String description,
        String overallStatus,
        Instant generatedAt,
        List<PublicMonitorStatus> monitors,
        List<PublicIncidentSummary> recentIncidents,
        List<PublicMaintenanceSummary> maintenanceWindows
) {

    public record PublicMonitorStatus(
            UUID id,
            String name,
            String currentStatus,
            Instant lastCheckedAt,
            Integer lastLatencyMs,
            Double uptime24h,
            Double uptime7d,
            Double uptime30d,
            List<DailyUptimeBucket> dailyUptime
    ) {
    }

    public record DailyUptimeBucket(
            String date,
            Double uptimePercent,
            long totalChecks
    ) {
    }

    public record PublicIncidentSummary(
            UUID id,
            String monitorName,
            String status,
            Instant startedAt,
            Instant resolvedAt,
            String summary
    ) {
    }

    public record PublicMaintenanceSummary(
            UUID id,
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            boolean active
    ) {
    }
}
