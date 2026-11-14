package com.opspulse.workspace.repository;

import com.opspulse.workspace.domain.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    boolean existsBySlug(String slug);

    Optional<Workspace> findBySlug(String slug);
}
