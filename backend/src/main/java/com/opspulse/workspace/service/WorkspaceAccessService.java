package com.opspulse.workspace.service;

import com.opspulse.common.exception.ForbiddenException;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.domain.WorkspaceMember;
import com.opspulse.workspace.domain.WorkspaceRole;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces workspace membership and role-based read/write authorization.
 */
@Service
public class WorkspaceAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceAccessService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional(readOnly = true)
    public WorkspaceMember requireReadAccess(UUID workspaceId, UUID userId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new ResourceNotFoundException("Workspace not found");
        }
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ForbiddenException("You do not have access to this workspace"));
    }

    @Transactional(readOnly = true)
    public Workspace requireWriteAccess(UUID workspaceId, UUID userId) {
        WorkspaceMember member = requireReadAccess(workspaceId, userId);
        if (member.getRole() == WorkspaceRole.VIEWER) {
            throw new ForbiddenException("Viewer role does not permit modifying workspace resources");
        }
        return member.getWorkspace();
    }
}
