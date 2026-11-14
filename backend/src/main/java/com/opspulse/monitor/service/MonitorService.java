package com.opspulse.monitor.service;

import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.domain.MonitorCheck;
import com.opspulse.monitor.domain.MonitorHttpMethod;
import com.opspulse.monitor.dto.CreateMonitorRequest;
import com.opspulse.monitor.dto.MonitorCheckResponse;
import com.opspulse.monitor.dto.MonitorResponse;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business operations for uptime monitors and check history.
 */
@Service
public class MonitorService {

    private static final int DEFAULT_INTERVAL_SECONDS = 60;
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_EXPECTED_STATUS = 200;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final UrlSafetyValidator urlSafetyValidator;

    public MonitorService(
            MonitorRepository monitorRepository,
            MonitorCheckRepository monitorCheckRepository,
            WorkspaceAccessService workspaceAccessService,
            UrlSafetyValidator urlSafetyValidator
    ) {
        this.monitorRepository = monitorRepository;
        this.monitorCheckRepository = monitorCheckRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.urlSafetyValidator = urlSafetyValidator;
    }

    @Transactional(readOnly = true)
    public List<MonitorResponse> listMonitors(UUID workspaceId, UUID userId) {
        workspaceAccessService.requireReadAccess(workspaceId, userId);
        return monitorRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MonitorResponse createMonitor(UUID workspaceId, UUID userId, CreateMonitorRequest request) {
        Workspace workspace = workspaceAccessService.requireWriteAccess(workspaceId, userId);
        String validatedUrl = urlSafetyValidator.validateAndNormalize(request.url());

        Monitor monitor = new Monitor(
                UUID.randomUUID(),
                workspace,
                request.name().trim(),
                validatedUrl,
                request.httpMethod() != null ? request.httpMethod() : MonitorHttpMethod.GET,
                request.intervalSeconds() != null ? request.intervalSeconds() : DEFAULT_INTERVAL_SECONDS,
                request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS,
                request.expectedStatus() != null ? request.expectedStatus() : DEFAULT_EXPECTED_STATUS,
                request.failureThreshold() != null ? request.failureThreshold() : DEFAULT_FAILURE_THRESHOLD
        );

        return toResponse(monitorRepository.save(monitor));
    }

    @Transactional
    public MonitorResponse setMonitorPaused(UUID workspaceId, UUID monitorId, UUID userId, Boolean paused) {
        workspaceAccessService.requireWriteAccess(workspaceId, userId);
        Monitor monitor = monitorRepository.findByIdAndWorkspaceId(monitorId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Monitor not found"));

        boolean targetPaused = paused != null ? paused : !monitor.isPaused();
        monitor.setPaused(targetPaused);
        return toResponse(monitorRepository.save(monitor));
    }

    @Transactional
    public void deleteMonitor(UUID workspaceId, UUID monitorId, UUID userId) {
        workspaceAccessService.requireWriteAccess(workspaceId, userId);
        Monitor monitor = monitorRepository.findByIdAndWorkspaceId(monitorId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Monitor not found"));
        monitorRepository.delete(monitor);
    }

    @Transactional(readOnly = true)
    public List<MonitorCheckResponse> listRecentChecks(UUID workspaceId, UUID monitorId, UUID userId) {
        workspaceAccessService.requireReadAccess(workspaceId, userId);
        Monitor monitor = monitorRepository.findByIdAndWorkspaceId(monitorId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Monitor not found"));

        return monitorCheckRepository.findTop50ByMonitorIdOrderByCheckedAtDesc(monitor.getId()).stream()
                .map(this::toCheckResponse)
                .toList();
    }

    private MonitorResponse toResponse(Monitor monitor) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getWorkspace().getId(),
                monitor.getName(),
                monitor.getUrl(),
                monitor.getHttpMethod(),
                monitor.getIntervalSeconds(),
                monitor.getTimeoutMs(),
                monitor.getExpectedStatus(),
                monitor.getFailureThreshold(),
                monitor.getConsecutiveFailures(),
                monitor.getCurrentStatus(),
                monitor.isPaused(),
                monitor.getNextCheckAt(),
                monitor.getLastCheckedAt(),
                monitor.getLastLatencyMs(),
                monitor.getCreatedAt()
        );
    }

    private MonitorCheckResponse toCheckResponse(MonitorCheck check) {
        return new MonitorCheckResponse(
                check.getId(),
                check.getMonitor().getId(),
                check.getStatus(),
                check.getStatusCode(),
                check.getLatencyMs(),
                check.getErrorMessage(),
                check.getCheckedAt()
        );
    }
}
