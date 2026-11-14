package com.opspulse.monitor.repository;

import com.opspulse.monitor.domain.Monitor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    List<Monitor> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<Monitor> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query("SELECT m FROM Monitor m WHERE m.paused = false AND m.nextCheckAt <= :now ORDER BY m.nextCheckAt ASC")
    List<Monitor> findDueMonitors(@Param("now") Instant now);
}
