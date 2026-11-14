package com.opspulse.monitor.repository;

import com.opspulse.monitor.domain.MonitorCheck;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, UUID> {

    List<MonitorCheck> findTop50ByMonitorIdOrderByCheckedAtDesc(UUID monitorId);

    @Query(
            value = """
                    SELECT mc.monitor_id AS monitorId,
                           COUNT(*) FILTER (WHERE mc.checked_at >= :since24h) AS total24h,
                           COUNT(*) FILTER (WHERE mc.checked_at >= :since24h AND mc.status = 'UP') AS up24h,
                           COUNT(*) FILTER (WHERE mc.checked_at >= :since7d) AS total7d,
                           COUNT(*) FILTER (WHERE mc.checked_at >= :since7d AND mc.status = 'UP') AS up7d,
                           COUNT(*) AS total30d,
                           COUNT(*) FILTER (WHERE mc.status = 'UP') AS up30d
                    FROM monitor_checks mc
                    JOIN monitors m ON m.id = mc.monitor_id
                    WHERE m.workspace_id = :workspaceId
                      AND mc.checked_at >= :since30d
                    GROUP BY mc.monitor_id
                    """,
            nativeQuery = true
    )
    List<MonitorUptimeSummaryProjection> findWorkspaceUptimeSummaries(
            @Param("workspaceId") UUID workspaceId,
            @Param("since24h") Instant since24h,
            @Param("since7d") Instant since7d,
            @Param("since30d") Instant since30d
    );

    @Query(
            value = """
                    SELECT mc.monitor_id AS monitorId,
                           TO_CHAR(DATE_TRUNC('day', mc.checked_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS bucketDate,
                           COUNT(*) AS totalChecks,
                           COUNT(*) FILTER (WHERE mc.status = 'UP') AS upChecks
                    FROM monitor_checks mc
                    JOIN monitors m ON m.id = mc.monitor_id
                    WHERE m.workspace_id = :workspaceId
                      AND mc.checked_at >= :since30d
                    GROUP BY mc.monitor_id, DATE_TRUNC('day', mc.checked_at AT TIME ZONE 'UTC')
                    ORDER BY DATE_TRUNC('day', mc.checked_at AT TIME ZONE 'UTC') ASC
                    """,
            nativeQuery = true
    )
    List<DailyUptimeBucketProjection> findWorkspaceDailyUptimeBuckets(
            @Param("workspaceId") UUID workspaceId,
            @Param("since30d") Instant since30d
    );

    interface MonitorUptimeSummaryProjection {
        UUID getMonitorId();

        long getTotal24h();

        long getUp24h();

        long getTotal7d();

        long getUp7d();

        long getTotal30d();

        long getUp30d();
    }

    interface DailyUptimeBucketProjection {
        UUID getMonitorId();

        String getBucketDate();

        long getTotalChecks();

        long getUpChecks();
    }
}
