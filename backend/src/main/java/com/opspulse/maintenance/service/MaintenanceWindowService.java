package com.opspulse.maintenance.service;

import com.opspulse.common.exception.BadRequestException;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.maintenance.domain.MaintenanceWindow;
import com.opspulse.maintenance.dto.CreateMaintenanceWindowRequest;
import com.opspulse.maintenance.dto.MaintenanceWindowResponse;
import com.opspulse.maintenance.repository.MaintenanceWindowRepository;
import com.opspulse.statuspage.domain.StatusPage;
import com.opspulse.statuspage.repository.StatusPageRepository;
import com.opspulse.statuspage.service.PublicStatusCacheService;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages workspace scheduled maintenance windows and evicts public status cache on changes.
 */
@Service
public class MaintenanceWindowService {

    private final MaintenanceWindowRepository maintenanceWindowRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final StatusPageRepository statusPageRepository;
    private final PublicStatusCacheService publicStatusCacheService;

    public MaintenanceWindowService(
            MaintenanceWindowRepository maintenanceWindowRepository,
            WorkspaceAccessService workspaceAccessService,
            StatusPageRepository statusPageRepository,
            PublicStatusCacheService publicStatusCacheService
    ) {
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.statusPageRepository = statusPageRepository;
        this.publicStatusCacheService = publicStatusCacheService;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> list(UUID workspaceId, UUID userId) {
        workspaceAccessService.requireReadAccess(workspaceId, userId);
        Instant now = Instant.now();
        return maintenanceWindowRepository.findAllByWorkspaceIdOrderByStartsAtDesc(workspaceId).stream()
                .map(window -> toResponse(window, now))
                .toList();
    }

    @Transactional
    public MaintenanceWindowResponse create(
            UUID workspaceId,
            UUID userId,
            CreateMaintenanceWindowRequest request
    ) {
        Workspace workspace = workspaceAccessService.requireWriteAccess(workspaceId, userId);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BadRequestException("Maintenance window end time must be after start time");
        }

        MaintenanceWindow window = new MaintenanceWindow(
                UUID.randomUUID(),
                workspace,
                request.title().trim(),
                normalizeDescription(request.description()),
                request.startsAt(),
                request.endsAt(),
                userId
        );

        MaintenanceWindow saved = maintenanceWindowRepository.save(window);
        evictWorkspacePublicStatusCacheAfterCommit(workspaceId);
        return toResponse(saved, Instant.now());
    }

    @Transactional
    public void delete(UUID workspaceId, UUID windowId, UUID userId) {
        workspaceAccessService.requireWriteAccess(workspaceId, userId);
        MaintenanceWindow window = maintenanceWindowRepository.findByIdAndWorkspaceId(windowId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Maintenance window not found"));
        maintenanceWindowRepository.delete(window);
        evictWorkspacePublicStatusCacheAfterCommit(workspaceId);
    }

    private void evictWorkspacePublicStatusCacheAfterCommit(UUID workspaceId) {
        statusPageRepository.findByWorkspaceId(workspaceId)
                .map(StatusPage::getSlug)
                .ifPresent(slug -> {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                publicStatusCacheService.evict(slug);
                            }
                        });
                    } else {
                        publicStatusCacheService.evict(slug);
                    }
                });
    }

    private MaintenanceWindowResponse toResponse(MaintenanceWindow window, Instant now) {
        return new MaintenanceWindowResponse(
                window.getId(),
                window.getWorkspace().getId(),
                window.getTitle(),
                window.getDescription(),
                window.getStartsAt(),
                window.getEndsAt(),
                window.isActiveAt(now),
                window.getCreatedAt()
        );
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
