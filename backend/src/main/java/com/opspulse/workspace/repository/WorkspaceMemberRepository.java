package com.opspulse.workspace.repository;

import com.opspulse.workspace.domain.WorkspaceMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    @EntityGraph(attributePaths = "workspace")
    List<WorkspaceMember> findAllByUserIdOrderByJoinedAtAsc(UUID userId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
