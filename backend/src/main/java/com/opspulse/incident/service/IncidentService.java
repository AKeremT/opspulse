package com.opspulse.incident.service;

import com.opspulse.alert.service.WebhookAlertService.PendingWebhookAlert;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages incident lifecycle transitions and builds post-commit notification payloads.
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
    private final SseNotificationService sseNotificationService;

    public IncidentService(
            IncidentRepository incidentRepository,
            WorkspaceAccessService workspaceAccessService,
            SseNotificationService sseNotificationService
    ) {
        this.incidentRepository = incidentRepository;
        this.workspaceAccessService = workspaceAccessService;
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
            IncidentChangedEvent event = toSseEvent(incident, incident.getMonitor());
            publishAfterCommit(workspaceId, event);
        }

        return toResponse(incident);
    }

    /**
     * Evaluates a check result against the monitor's failure threshold and active incidents.
     * Returns an {@link IncidentTransition} to be dispatched after the caller's transaction commits.
     */
    @Transactional
    public Optional<IncidentTransition> handleCheckResult(
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
                return Optional.of(toTransition("incident.opened", incident, monitor));
            }
        } else if (checkStatus == MonitorStatus.UP) {
            Optional<Incident> activeOpt = incidentRepository
                    .findFirstByMonitorIdAndStatusInOrderByStartedAtDesc(monitor.getId(), ACTIVE_STATUSES);
            if (activeOpt.isPresent()) {
                Incident activeIncident = activeOpt.get();
                activeIncident.resolve(checkedAt);
                Incident resolved = incidentRepository.save(activeIncident);
                return Optional.of(toTransition("incident.resolved", resolved, monitor));
            }
        }
        return Optional.empty();
    }

    private void publishAfterCommit(UUID workspaceId, IncidentChangedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseNotificationService.publishIncidentChanged(workspaceId, event);
                }
            });
        } else {
            sseNotificationService.publishIncidentChanged(workspaceId, event);
        }
    }

    private IncidentTransition toTransition(String eventType, Incident incident, Monitor monitor) {
        UUID workspaceId = monitor.getWorkspace().getId();
        return new IncidentTransition(
                workspaceId,
                toSseEvent(incident, monitor),
                new PendingWebhookAlert(
                        workspaceId,
                        eventType,
                        incident.getId(),
                        monitor.getId(),
                        monitor.getName(),
                        monitor.getUrl(),
                        incident.getStatus().name(),
                        incident.getSummary()
                )
        );
    }

    private IncidentChangedEvent toSseEvent(Incident incident, Monitor monitor) {
        return new IncidentChangedEvent(
                incident.getId(),
                monitor.getId(),
                incident.getWorkspace().getId(),
                monitor.getName(),
                incident.getStatus(),
                incident.getSummary(),
                incident.getStartedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt()
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

    public record IncidentTransition(
            UUID workspaceId,
            IncidentChangedEvent sseEvent,
            PendingWebhookAlert webhookAlert
    ) {
    }
}
