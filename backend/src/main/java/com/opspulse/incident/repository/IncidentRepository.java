package com.opspulse.incident.repository;

import com.opspulse.incident.domain.Incident;
import com.opspulse.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    @EntityGraph(attributePaths = "monitor")
    List<Incident> findAllByWorkspaceIdOrderByStartedAtDesc(UUID workspaceId);

    @EntityGraph(attributePaths = "monitor")
    List<Incident> findTop10ByWorkspaceIdAndStartedAtAfterOrderByStartedAtDesc(
            UUID workspaceId,
            Instant since
    );

    @EntityGraph(attributePaths = "monitor")
    Optional<Incident> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<Incident> findFirstByMonitorIdAndStatusInOrderByStartedAtDesc(
            UUID monitorId,
            Collection<IncidentStatus> statuses
    );
}
