package com.opspulse.monitor.repository;

import com.opspulse.monitor.domain.Monitor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    List<Monitor> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<Monitor> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
