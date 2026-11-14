package com.opspulse.monitor.worker;

import com.opspulse.incident.service.IncidentService;
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
 * Periodically polls due monitors and executes health checks concurrently
 * using Java 21 virtual threads.
 */
@Component
public class MonitorCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorCheckScheduler.class);

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final MonitorCheckExecutor checkExecutor;
    private final IncidentService incidentService;
    private final SseNotificationService sseNotificationService;
    private final TransactionTemplate transactionTemplate;

    public MonitorCheckScheduler(
            MonitorRepository monitorRepository,
            MonitorCheckRepository monitorCheckRepository,
            MonitorCheckExecutor checkExecutor,
            IncidentService incidentService,
            SseNotificationService sseNotificationService,
            TransactionTemplate transactionTemplate
    ) {
        this.monitorRepository = monitorRepository;
        this.monitorCheckRepository = monitorCheckRepository;
        this.checkExecutor = checkExecutor;
        this.incidentService = incidentService;
        this.sseNotificationService = sseNotificationService;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.monitor.scheduler-poll-ms:5000}")
    public void scheduledPoll() {
        runDueChecks();
    }

    /**
     * Finds all non-paused monitors whose {@code nextCheckAt <= now}, executes their
     * HTTP probes concurrently on virtual threads, and persists the check outcomes.
     *
     * @return number of monitors checked in this cycle
     */
    public int runDueChecks() {
        List<MonitorProbeTarget> targets = transactionTemplate.execute(status -> {
            List<Monitor> dueMonitors = monitorRepository.findDueMonitors(Instant.now());
            return dueMonitors.stream()
                    .map(m -> new MonitorProbeTarget(
                            m.getId(),
                            m.getUrl(),
                            m.getHttpMethod(),
                            m.getTimeoutMs(),
                            m.getExpectedStatus()
                    ))
                    .toList();
        });

        if (targets == null || targets.isEmpty()) {
            return 0;
        }

        List<ProbeOutcome> outcomes = executeConcurrently(targets);

        transactionTemplate.executeWithoutResult(status -> persistOutcomes(outcomes));
        return outcomes.size();
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

    private void persistOutcomes(List<ProbeOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }

        List<UUID> monitorIds = outcomes.stream().map(ProbeOutcome::monitorId).toList();
        Map<UUID, Monitor> monitorsById = monitorRepository.findAllById(monitorIds).stream()
                .collect(Collectors.toMap(Monitor::getId, Function.identity()));

        List<MonitorCheck> checksToSave = new ArrayList<>(outcomes.size());
        List<MonitorCheckedEvent> sseEvents = new ArrayList<>(outcomes.size());

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
            sseEvents.add(new MonitorCheckedEvent(
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
                );
            }
        }

        for (MonitorCheckedEvent event : sseEvents) {
            sseNotificationService.publishMonitorChecked(event.workspaceId(), event);
        }
    }
}
