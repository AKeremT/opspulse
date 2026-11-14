package com.opspulse.monitor.repository;

import com.opspulse.monitor.domain.Monitor;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    List<Monitor> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<Monitor> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query(
            value = """
                    SELECT * FROM monitors
                    WHERE paused = false AND next_check_at <= :now
                    ORDER BY next_check_at ASC
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<Monitor> findDueMonitorsForUpdate(@Param("now") Instant now);

    @EntityGraph(attributePaths = "workspace")
    List<Monitor> findAllWithWorkspaceByIdIn(Collection<UUID> ids);
}
