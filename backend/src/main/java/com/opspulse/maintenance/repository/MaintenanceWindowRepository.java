package com.opspulse.maintenance.repository;

import com.opspulse.maintenance.domain.MaintenanceWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, UUID> {

    List<MaintenanceWindow> findAllByWorkspaceIdOrderByStartsAtDesc(UUID workspaceId);

    Optional<MaintenanceWindow> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MaintenanceWindow m
            WHERE m.workspace.id = :workspaceId
              AND m.startsAt <= :at
              AND m.endsAt > :at
            """)
    boolean existsActiveWindow(@Param("workspaceId") UUID workspaceId, @Param("at") Instant at);

    @Query("""
            SELECT m FROM MaintenanceWindow m
            WHERE m.workspace.id = :workspaceId
              AND m.endsAt > :now
            ORDER BY m.startsAt ASC
            """)
    List<MaintenanceWindow> findActiveAndUpcomingByWorkspaceId(
            @Param("workspaceId") UUID workspaceId,
            @Param("now") Instant now
    );
}
