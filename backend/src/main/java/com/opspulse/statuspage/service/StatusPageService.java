package com.opspulse.statuspage.service;

import com.opspulse.common.exception.ConflictException;
import com.opspulse.common.exception.ResourceNotFoundException;
import com.opspulse.incident.domain.Incident;
import com.opspulse.incident.repository.IncidentRepository;
import com.opspulse.maintenance.domain.MaintenanceWindow;
import com.opspulse.maintenance.repository.MaintenanceWindowRepository;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.domain.MonitorStatus;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorCheckRepository.DailyUptimeBucketProjection;
import com.opspulse.monitor.repository.MonitorCheckRepository.MonitorUptimeSummaryProjection;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.statuspage.domain.StatusPage;
import com.opspulse.statuspage.dto.PublicStatusResponse;
import com.opspulse.statuspage.dto.PublicStatusResponse.DailyUptimeBucket;
import com.opspulse.statuspage.dto.PublicStatusResponse.PublicIncidentSummary;
import com.opspulse.statuspage.dto.PublicStatusResponse.PublicMaintenanceSummary;
import com.opspulse.statuspage.dto.PublicStatusResponse.PublicMonitorStatus;
import com.opspulse.statuspage.dto.StatusPageSettingsResponse;
import com.opspulse.statuspage.dto.UpdateStatusPageRequest;
import com.opspulse.statuspage.repository.StatusPageRepository;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.domain.WorkspaceMember;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manages workspace status page settings and builds public status payloads
 * with aggregated uptime metrics and short-TTL Redis caching.
 */
@Service
public class StatusPageService {

    private static final int DAILY_HISTORY_DAYS = 30;

    private final StatusPageRepository statusPageRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final IncidentRepository incidentRepository;
    private final MaintenanceWindowRepository maintenanceWindowRepository;
    private final PublicStatusCacheService cacheService;
    private final TransactionTemplate readOnlyTransactionTemplate;

    public StatusPageService(
            StatusPageRepository statusPageRepository,
            WorkspaceAccessService workspaceAccessService,
            MonitorRepository monitorRepository,
            MonitorCheckRepository monitorCheckRepository,
            IncidentRepository incidentRepository,
            MaintenanceWindowRepository maintenanceWindowRepository,
            PublicStatusCacheService cacheService,
            PlatformTransactionManager transactionManager
    ) {
        this.statusPageRepository = statusPageRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.monitorRepository = monitorRepository;
        this.monitorCheckRepository = monitorCheckRepository;
        this.incidentRepository = incidentRepository;
        this.maintenanceWindowRepository = maintenanceWindowRepository;
        this.cacheService = cacheService;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    @Transactional
    public StatusPageSettingsResponse getSettings(UUID workspaceId, UUID userId) {
        WorkspaceMember member = workspaceAccessService.requireReadAccess(workspaceId, userId);
        Workspace workspace = member.getWorkspace();

        StatusPage statusPage = statusPageRepository.findByWorkspaceId(workspaceId)
                .orElseGet(() -> createDefaultStatusPage(workspace));

        return StatusPageSettingsResponse.from(statusPage);
    }

    @Transactional
    public StatusPageSettingsResponse updateSettings(
            UUID workspaceId,
            UUID userId,
            UpdateStatusPageRequest request
    ) {
        Workspace workspace = workspaceAccessService.requireAdminAccess(workspaceId, userId);
        String normalizedSlug = request.slug().trim().toLowerCase(Locale.ROOT);

        if (statusPageRepository.existsBySlugAndWorkspaceIdNot(normalizedSlug, workspaceId)) {
            throw new ConflictException("Status page slug is already in use");
        }

        Optional<StatusPage> existingOpt = statusPageRepository.findByWorkspaceId(workspaceId);
        String oldSlug = existingOpt.map(StatusPage::getSlug).orElse(null);

        StatusPage statusPage = existingOpt.orElseGet(() -> new StatusPage(
                UUID.randomUUID(),
                workspace,
                normalizedSlug,
                request.title().trim(),
                normalizeDescription(request.description()),
                request.isPublic()
        ));

        statusPage.updateSettings(
                normalizedSlug,
                request.title().trim(),
                normalizeDescription(request.description()),
                request.isPublic()
        );

        StatusPage saved = statusPageRepository.save(statusPage);
        evictCacheAfterCommit(oldSlug, normalizedSlug);

        return StatusPageSettingsResponse.from(saved);
    }

    public PublicStatusResponse getPublicStatus(String slug) {
        String normalizedSlug = slug.trim().toLowerCase(Locale.ROOT);

        Optional<PublicStatusResponse> cached = cacheService.get(normalizedSlug);
        if (cached.isPresent()) {
            return cached.get();
        }

        PublicStatusResponse response = readOnlyTransactionTemplate.execute(
                status -> loadPublicStatusFromDatabase(normalizedSlug)
        );
        if (response != null) {
            cacheService.put(normalizedSlug, response);
        }
        return response;
    }

    private void evictCacheAfterCommit(String oldSlug, String newSlug) {
        Runnable evictTask = () -> {
            if (oldSlug != null && !oldSlug.equals(newSlug)) {
                cacheService.evict(oldSlug);
            }
            cacheService.evict(newSlug);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictTask.run();
                }
            });
        } else {
            evictTask.run();
        }
    }

    private PublicStatusResponse loadPublicStatusFromDatabase(String normalizedSlug) {
        StatusPage statusPage = statusPageRepository.findBySlugAndIsPublicTrue(normalizedSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Public status page not found"));

        UUID workspaceId = statusPage.getWorkspace().getId();
        Instant now = Instant.now();
        Instant since24h = now.minus(24, ChronoUnit.HOURS);
        Instant since7d = now.minus(7, ChronoUnit.DAYS);
        Instant since30d = now.minus(DAILY_HISTORY_DAYS, ChronoUnit.DAYS);

        List<Monitor> monitors = monitorRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);

        Map<UUID, MonitorUptimeSummaryProjection> summaryByMonitor = monitorCheckRepository
                .findWorkspaceUptimeSummaries(workspaceId, since24h, since7d, since30d)
                .stream()
                .collect(Collectors.toMap(MonitorUptimeSummaryProjection::getMonitorId, Function.identity()));

        Map<UUID, Map<String, DailyUptimeBucketProjection>> dailyByMonitor = new HashMap<>();
        for (DailyUptimeBucketProjection bucket :
                monitorCheckRepository.findWorkspaceDailyUptimeBuckets(workspaceId, since30d)) {
            dailyByMonitor
                    .computeIfAbsent(bucket.getMonitorId(), ignored -> new HashMap<>())
                    .put(bucket.getBucketDate(), bucket);
        }

        List<String> dateWindow = buildDateWindow();
        List<PublicMonitorStatus> monitorStatuses = monitors.stream()
                .map(monitor -> toPublicMonitorStatus(
                        monitor,
                        summaryByMonitor.get(monitor.getId()),
                        dailyByMonitor.getOrDefault(monitor.getId(), Map.of()),
                        dateWindow
                ))
                .toList();

        String overallStatus = computeOverallStatus(monitors);

        List<PublicIncidentSummary> recentIncidents = incidentRepository
                .findTop10ByWorkspaceIdAndStartedAtAfterOrderByStartedAtDesc(workspaceId, since30d)
                .stream()
                .map(this::toPublicIncidentSummary)
                .toList();

        List<PublicMaintenanceSummary> maintenanceWindows = maintenanceWindowRepository
                .findActiveAndUpcomingByWorkspaceId(workspaceId, now)
                .stream()
                .map(window -> toPublicMaintenanceSummary(window, now))
                .toList();

        return new PublicStatusResponse(
                statusPage.getSlug(),
                statusPage.getTitle(),
                statusPage.getDescription(),
                overallStatus,
                now,
                monitorStatuses,
                recentIncidents,
                maintenanceWindows
        );
    }

    private StatusPage createDefaultStatusPage(Workspace workspace) {
        String candidateSlug = workspace.getSlug();
        if (statusPageRepository.existsBySlugAndWorkspaceIdNot(candidateSlug, workspace.getId())) {
            candidateSlug = candidateSlug + "-" + workspace.getId().toString().substring(0, 8);
        }
        String defaultTitle = workspace.getName() + " Status";
        if (defaultTitle.length() > 160) {
            defaultTitle = defaultTitle.substring(0, 160);
        }
        StatusPage defaultPage = new StatusPage(
                UUID.randomUUID(),
                workspace,
                candidateSlug,
                defaultTitle,
                null,
                false
        );
        return statusPageRepository.save(defaultPage);
    }

    private PublicMonitorStatus toPublicMonitorStatus(
            Monitor monitor,
            MonitorUptimeSummaryProjection summary,
            Map<String, DailyUptimeBucketProjection> dailyMap,
            List<String> dateWindow
    ) {
        Double uptime24h = summary == null ? null : calculatePercentage(summary.getUp24h(), summary.getTotal24h());
        Double uptime7d = summary == null ? null : calculatePercentage(summary.getUp7d(), summary.getTotal7d());
        Double uptime30d = summary == null ? null : calculatePercentage(summary.getUp30d(), summary.getTotal30d());

        List<DailyUptimeBucket> dailyUptime = dateWindow.stream()
                .map(date -> {
                    DailyUptimeBucketProjection bucket = dailyMap.get(date);
                    if (bucket == null) {
                        return new DailyUptimeBucket(date, null, 0L);
                    }
                    return new DailyUptimeBucket(
                            date,
                            calculatePercentage(bucket.getUpChecks(), bucket.getTotalChecks()),
                            bucket.getTotalChecks()
                    );
                })
                .toList();

        return new PublicMonitorStatus(
                monitor.getId(),
                monitor.getName(),
                monitor.getCurrentStatus().name(),
                monitor.getLastCheckedAt(),
                monitor.getLastLatencyMs(),
                uptime24h,
                uptime7d,
                uptime30d,
                dailyUptime
        );
    }

    private PublicIncidentSummary toPublicIncidentSummary(Incident incident) {
        return new PublicIncidentSummary(
                incident.getId(),
                incident.getMonitor().getName(),
                incident.getStatus().name(),
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getSummary()
        );
    }

    private PublicMaintenanceSummary toPublicMaintenanceSummary(MaintenanceWindow window, Instant now) {
        return new PublicMaintenanceSummary(
                window.getId(),
                window.getTitle(),
                window.getDescription(),
                window.getStartsAt(),
                window.getEndsAt(),
                window.isActiveAt(now)
        );
    }

    private String computeOverallStatus(List<Monitor> monitors) {
        long activeCount = monitors.stream().filter(m -> !m.isPaused()).count();
        long downCount = monitors.stream()
                .filter(m -> !m.isPaused() && m.getCurrentStatus() == MonitorStatus.DOWN)
                .count();
        if (downCount == 0) {
            return "OPERATIONAL";
        }
        if (downCount == activeCount) {
            return "MAJOR_OUTAGE";
        }
        return "DEGRADED";
    }

    private List<String> buildDateWindow() {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        List<String> dates = new ArrayList<>(DAILY_HISTORY_DAYS);
        for (int i = DAILY_HISTORY_DAYS - 1; i >= 0; i--) {
            dates.add(todayUtc.minusDays(i).toString());
        }
        return dates;
    }

    private Double calculatePercentage(long upCount, long totalCount) {
        if (totalCount <= 0) {
            return null;
        }
        return Math.round((upCount * 10000.0) / totalCount) / 100.0;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
