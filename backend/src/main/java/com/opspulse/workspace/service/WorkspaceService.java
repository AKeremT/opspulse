package com.opspulse.workspace.service;

import com.opspulse.auth.domain.User;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.common.exception.ConflictException;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.domain.WorkspaceMember;
import com.opspulse.workspace.domain.WorkspaceRole;
import com.opspulse.workspace.dto.CreateWorkspaceRequest;
import com.opspulse.workspace.dto.WorkspaceResponse;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business operations for workspaces and workspace memberships.
 */
@Service
public class WorkspaceService {

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_SLUG_LENGTH = 120;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listUserWorkspaces(UUID userId) {
        return workspaceMemberRepository.findAllByUserIdOrderByJoinedAtAsc(userId).stream()
                .map(member -> toResponse(member.getWorkspace(), member.getRole()))
                .toList();
    }

    @Transactional
    public WorkspaceResponse createWorkspace(UUID userId, CreateWorkspaceRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String name = request.name().trim();
        String slug = resolveSlug(request.slug(), name);

        return createWorkspaceWithOwner(owner, name, slug);
    }

    @Transactional
    public WorkspaceResponse createDefaultWorkspaceForUser(User user) {
        String rawName = user.getFullName().trim() + "'s Workspace";
        String name = rawName.length() > MAX_NAME_LENGTH
                ? rawName.substring(0, MAX_NAME_LENGTH).trim()
                : rawName;
        String slug = generateUniqueSlug(user.getFullName() + "-workspace");

        return createWorkspaceWithOwner(user, name, slug);
    }

    private WorkspaceResponse createWorkspaceWithOwner(User owner, String name, String slug) {
        Workspace workspace = workspaceRepository.save(
                new Workspace(UUID.randomUUID(), name, slug, owner)
        );
        workspaceMemberRepository.save(
                new WorkspaceMember(UUID.randomUUID(), workspace, owner, WorkspaceRole.OWNER)
        );
        return toResponse(workspace, WorkspaceRole.OWNER);
    }

    private String resolveSlug(String requestedSlug, String fallbackName) {
        if (requestedSlug != null && !requestedSlug.isBlank()) {
            String normalized = requestedSlug.trim().toLowerCase(Locale.ROOT);
            if (workspaceRepository.existsBySlug(normalized)) {
                throw new ConflictException("Workspace slug is already in use");
            }
            return normalized;
        }
        return generateUniqueSlug(fallbackName);
    }

    private String generateUniqueSlug(String source) {
        String base = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (base.isEmpty()) {
            base = "workspace";
        }
        if (base.length() > MAX_SLUG_LENGTH - 9) {
            base = base.substring(0, MAX_SLUG_LENGTH - 9).replaceAll("-+$", "");
        }

        if (!workspaceRepository.existsBySlug(base)) {
            return base;
        }

        String candidate;
        do {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            candidate = base + "-" + suffix;
        } while (workspaceRepository.existsBySlug(candidate));

        return candidate;
    }

    private WorkspaceResponse toResponse(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getSlug(),
                role,
                workspace.getCreatedAt()
        );
    }
}
