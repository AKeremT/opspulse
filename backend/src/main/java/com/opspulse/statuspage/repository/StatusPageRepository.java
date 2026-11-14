package com.opspulse.statuspage.repository;

import com.opspulse.statuspage.domain.StatusPage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusPageRepository extends JpaRepository<StatusPage, UUID> {

    Optional<StatusPage> findByWorkspaceId(UUID workspaceId);

    Optional<StatusPage> findBySlugAndIsPublicTrue(String slug);

    boolean existsBySlugAndWorkspaceIdNot(String slug, UUID workspaceId);
}
