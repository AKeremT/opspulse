package com.opspulse.incident.service;

import com.opspulse.alert.service.WebhookAlertService;
import com.opspulse.common.exception.ConflictException;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.incident.domain.Incident;
import com.opspulse.incident.domain.IncidentStatus;
import com.opspulse.incident.dto.IncidentResponse;
import com.opspulse.incident.repository.IncidentRepository;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.domain.MonitorStatus;
import com.opspulse.notification.dto.IncidentChangedEvent;
import com.opspulse.notification.service.SseNotificationService;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages incident lifecycle transitions, SSE broadcasts, and webhook alerts.
 */
@Service
public class IncidentService {

    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final List<IncidentStatus> ACTIVE_STATUSES = List.of(
            IncidentStatus.OPEN,
            IncidentStatus.ACKNOWLEDGED
    );

    private final IncidentRepository incidentRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final WebhookAlertService webhookAlertService;
    private final SseNotificationService sseNotificationService;

    public IncidentService(
            IncidentRepository incidentRepository,
            WorkspaceAccessService workspaceAccessService,
            WebhookAlertService webhookAlertService,
            SseNotificationService sseNotificationService
    ) {
        this.incidentRepository = incidentRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.webhookAlertService = webhookAlertService;
        this.sseNotificationService = sseNotificationService;
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> listIncidents(UUID workspaceId, UUID userId) {
        workspaceAccessService.requireReadAccess(workspaceId, userId);
        return incidentRepository.findAllByWorkspaceIdOrderByStartedAtDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public IncidentResponse acknowledgeIncident(UUID workspaceId, UUID incidentId, UUID userId) {
        workspaceAccessService.requireWriteAccess(workspaceId, userId);
        Incident incident = incidentRepository.findByIdAndWorkspaceId(incidentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));

        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            throw new ConflictException("Resolved incident cannot be acknowledged");
        }
        if (incident.getStatus() == IncidentStatus.OPEN) {
            incident.acknowledge(Instant.now());
            incident = incidentRepository.save(incident);
            publishIncidentSse(incident, incident.getMonitor());
        }

        return toResponse(incident);
    }

    @Transactional
    public void handleCheckResult(
            Monitor monitor,
            MonitorStatus checkStatus,
            Instant checkedAt,
            String errorMessage
    ) {
        if (checkStatus == MonitorStatus.DOWN
                && monitor.getConsecutiveFailures() >= monitor.getFailureThreshold()) {
            boolean hasActive = incidentRepository
                    .findFirstByMonitorIdAndStatusInOrderByStartedAtDesc(monitor.getId(), ACTIVE_STATUSES)
                    .isPresent();
            if (!hasActive) {
                String summary = truncateSummary(
                        errorMessage != null && !errorMessage.isBlank()
                                ? errorMessage
                                : "Monitor failed " + monitor.getConsecutiveFailures() + " consecutive checks"
                );
                Incident incident = incidentRepository.save(new Incident(
                        UUID.randomUUID(),
                        monitor,
                        monitor.getWorkspace(),
                        IncidentStatus.OPEN,
                        checkedAt,
                        summary
                ));
                UUID workspaceId = monitor.getWorkspace().getId();
                webhookAlertService.sendIncidentAlert(workspaceId, "incident.opened", incident, monitor);
                publishIncidentSse(incident, monitor);
            }
        } else if (checkStatus == MonitorStatus.UP) {
            incidentRepository
                    .findFirstByMonitorIdAndStatusInOrderByStartedAtDesc(monitor.getId(), ACTIVE_STATUSES)
                    .ifPresent(activeIncident -> {
                        activeIncident.resolve(checkedAt);
                        Incident resolved = incidentRepository.save(activeIncident);
                        UUID workspaceId = monitor.getWorkspace().getId();
                        webhookAlertService.sendIncidentAlert(workspaceId, "incident.resolved", resolved, monitor);
                        publishIncidentSse(resolved, monitor);
                    });
        }
    }

    private void publishIncidentSse(Incident incident, Monitor monitor) {
        UUID workspaceId = incident.getWorkspace().getId();
        sseNotificationService.publishIncidentChanged(
                workspaceId,
                new IncidentChangedEvent(
                        incident.getId(),
                        monitor.getId(),
                        workspaceId,
                        monitor.getName(),
                        incident.getStatus(),
                        incident.getSummary(),
                        incident.getStartedAt(),
                        incident.getAcknowledgedAt(),
                        incident.getResolvedAt()
                )
        );
    }

    private IncidentResponse toResponse(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getMonitor().getId(),
                incident.getMonitor().getName(),
                incident.getWorkspace().getId(),
                incident.getStatus(),
                incident.getStartedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt(),
                incident.getSummary()
        );
    }

    private String truncateSummary(String summary) {
        return summary.length() <= MAX_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_SUMMARY_LENGTH);
    }
}
