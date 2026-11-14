package com.opspulse.alert.service;

import com.opspulse.alert.domain.AlertChannel;
import com.opspulse.alert.domain.AlertChannelType;
import com.opspulse.alert.dto.AlertChannelResponse;
import com.opspulse.alert.dto.CreateAlertChannelRequest;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.monitor.service.UrlSafetyValidator;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business operations for managing workspace alert notification channels.
 */
@Service
public class AlertChannelService {

    private final AlertChannelRepository alertChannelRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final UrlSafetyValidator urlSafetyValidator;

    public AlertChannelService(
            AlertChannelRepository alertChannelRepository,
            WorkspaceAccessService workspaceAccessService,
            UrlSafetyValidator urlSafetyValidator
    ) {
        this.alertChannelRepository = alertChannelRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.urlSafetyValidator = urlSafetyValidator;
    }

    @Transactional(readOnly = true)
    public List<AlertChannelResponse> listChannels(UUID workspaceId, UUID userId) {
        workspaceAccessService.requireReadAccess(workspaceId, userId);
        return alertChannelRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AlertChannelResponse createChannel(UUID workspaceId, UUID userId, CreateAlertChannelRequest request) {
        Workspace workspace = workspaceAccessService.requireWriteAccess(workspaceId, userId);
        String validatedUrl = urlSafetyValidator.validateAndNormalize(request.targetUrl());

        AlertChannel channel = new AlertChannel(
                UUID.randomUUID(),
                workspace,
                request.name().trim(),
                request.channelType() != null ? request.channelType() : AlertChannelType.WEBHOOK,
                validatedUrl
        );

        return toResponse(alertChannelRepository.save(channel));
    }

    @Transactional
    public void deleteChannel(UUID workspaceId, UUID channelId, UUID userId) {
        workspaceAccessService.requireWriteAccess(workspaceId, userId);
        AlertChannel channel = alertChannelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert channel not found"));
        alertChannelRepository.delete(channel);
    }

    private AlertChannelResponse toResponse(AlertChannel channel) {
        return new AlertChannelResponse(
                channel.getId(),
                channel.getWorkspace().getId(),
                channel.getName(),
                channel.getChannelType(),
                channel.getTargetUrl(),
                channel.isEnabled(),
                channel.getCreatedAt()
        );
    }
}
