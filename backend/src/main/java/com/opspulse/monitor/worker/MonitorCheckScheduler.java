package com.opspulse.monitor.worker;

import com.opspulse.alert.service.WebhookAlertService;
import com.opspulse.incident.service.IncidentService;
import com.opspulse.incident.service.IncidentService.IncidentTransition;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.domain.MonitorCheck;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.monitor.worker.MonitorCheckExecutor.MonitorProbeTarget;
import com.opspulse.monitor.worker.MonitorCheckExecutor.ProbeOutcome;
import com.opspulse.notification.dto.MonitorCheckedEvent;
import com.opspulse.notification.service.SseNotificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodically claims due monitors using row-level locking, executes health checks
 * concurrently on Java 21 virtual threads, persists outcomes, and dispatches
 * SSE/webhook notifications after transaction commit.
 */
@Component
public class MonitorCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorCheckScheduler.class);

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final MonitorCheckExecutor checkExecutor;
    private final IncidentService incidentService;
    private final WebhookAlertService webhookAlertService;
    private final SseNotificationService sseNotificationService;
    private final TransactionTemplate transactionTemplate;

    public MonitorCheckScheduler(
            MonitorRepository monitorRepository,
            MonitorCheckRepository monitorCheckRepository,
            MonitorCheckExecutor checkExecutor,
            IncidentService incidentService,
            WebhookAlertService webhookAlertService,
            SseNotificationService sseNotificationService,
            TransactionTemplate transactionTemplate
    ) {
        this.monitorRepository = monitorRepository;
        this.monitorCheckRepository = monitorCheckRepository;
        this.checkExecutor = checkExecutor;
        this.incidentService = incidentService;
        this.webhookAlertService = webhookAlertService;
        this.sseNotificationService = sseNotificationService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.monitor.scheduler-poll-ms:5000}")
    public void scheduledPoll() {
        runDueChecks();
    }

    /**
     * Atomically claims non-paused monitors whose {@code nextCheckAt <= now},
     * executes HTTP probes concurrently on virtual threads, persists check outcomes,
     * and dispatches SSE and webhook notifications after commit.
     *
     * @return number of monitors checked in this cycle
     */
    public int runDueChecks() {
        List<MonitorProbeTarget> targets = transactionTemplate.execute(status -> claimDueMonitors());

        if (targets == null || targets.isEmpty()) {
            return 0;
        }

        List<ProbeOutcome> outcomes = executeConcurrently(targets);

        PendingNotifications pending = transactionTemplate.execute(status -> persistOutcomes(outcomes));
        if (pending != null) {
            dispatchAfterCommit(pending);
        }

        return outcomes.size();
    }

    private List<MonitorProbeTarget> claimDueMonitors() {
        Instant now = Instant.now();
        List<Monitor> dueMonitors = monitorRepository.findDueMonitorsForUpdate(now);
        if (dueMonitors.isEmpty()) {
            return List.of();
        }

        for (Monitor monitor : dueMonitors) {
            monitor.claimForCheck(now);
        }
        monitorRepository.saveAll(dueMonitors);

        return dueMonitors.stream()
                .map(m -> new MonitorProbeTarget(
                        m.getId(),
                        m.getUrl(),
                        m.getHttpMethod(),
                        m.getTimeoutMs(),
                        m.getExpectedStatus()
                ))
                .toList();
    }

    private List<ProbeOutcome> executeConcurrently(List<MonitorProbeTarget> targets) {
        List<ProbeOutcome> outcomes = new ArrayList<>(targets.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ProbeOutcome>> futures = targets.stream()
                    .map(target -> executor.submit(() -> checkExecutor.execute(target)))
                    .toList();

            for (Future<ProbeOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("Monitor check batch interrupted", ex);
                    break;
                } catch (ExecutionException ex) {
                    log.error("Unexpected error executing monitor probe", ex.getCause());
                }
            }
        }
        return outcomes;
    }

    private PendingNotifications persistOutcomes(List<ProbeOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new PendingNotifications(List.of(), List.of());
        }

        List<UUID> monitorIds = outcomes.stream().map(ProbeOutcome::monitorId).toList();
        Map<UUID, Monitor> monitorsById = monitorRepository.findAllWithWorkspaceByIdIn(monitorIds).stream()
                .collect(Collectors.toMap(Monitor::getId, Function.identity()));

        List<MonitorCheck> checksToSave = new ArrayList<>(outcomes.size());
        List<MonitorCheckedEvent> monitorEvents = new ArrayList<>(outcomes.size());
        List<IncidentTransition> incidentTransitions = new ArrayList<>();

        for (ProbeOutcome outcome : outcomes) {
            Monitor monitor = monitorsById.get(outcome.monitorId());
            if (monitor == null) {
                continue;
            }
            monitor.recordCheckResult(outcome.status(), outcome.latencyMs(), outcome.checkedAt());
            checksToSave.add(new MonitorCheck(
                    UUID.randomUUID(),
                    monitor,
                    outcome.status(),
                    outcome.statusCode(),
                    outcome.latencyMs(),
                    outcome.errorMessage()
            ));

            UUID workspaceId = monitor.getWorkspace().getId();
            monitorEvents.add(new MonitorCheckedEvent(
                    monitor.getId(),
                    workspaceId,
                    monitor.getName(),
                    outcome.status(),
                    outcome.statusCode(),
                    outcome.latencyMs(),
                    monitor.getConsecutiveFailures(),
                    outcome.checkedAt()
            ));
        }

        monitorCheckRepository.saveAll(checksToSave);
        monitorRepository.saveAll(monitorsById.values());

        for (ProbeOutcome outcome : outcomes) {
            Monitor monitor = monitorsById.get(outcome.monitorId());
            if (monitor != null) {
                incidentService.handleCheckResult(
                        monitor,
                        outcome.status(),
                        outcome.checkedAt(),
                        outcome.errorMessage()
                ).ifPresent(incidentTransitions::add);
            }
        }

        return new PendingNotifications(monitorEvents, incidentTransitions);
    }

    private void dispatchAfterCommit(PendingNotifications pending) {
        for (IncidentTransition transition : pending.incidentTransitions()) {
            webhookAlertService.sendIncidentAlert(transition.webhookAlert());
            sseNotificationService.publishIncidentChanged(transition.workspaceId(), transition.sseEvent());
        }
        for (MonitorCheckedEvent event : pending.monitorEvents()) {
            sseNotificationService.publishMonitorChecked(event.workspaceId(), event);
        }
    }

    private record PendingNotifications(
            List<MonitorCheckedEvent> monitorEvents,
            List<IncidentTransition> incidentTransitions
    ) {
    }
}
