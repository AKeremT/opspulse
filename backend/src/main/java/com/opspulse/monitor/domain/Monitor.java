package com.opspulse.monitor.domain;

import com.opspulse.workspace.domain.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitors")
public class Monitor {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 10)
    private MonitorHttpMethod httpMethod = MonitorHttpMethod.GET;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds = 60;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 5000;

    @Column(name = "expected_status", nullable = false)
    private int expectedStatus = 200;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold = 3;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 16)
    private MonitorStatus currentStatus = MonitorStatus.UNKNOWN;

    @Column(name = "paused", nullable = false)
    private boolean paused = false;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_latency_ms")
    private Integer lastLatencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Monitor() {
    }

    public Monitor(
            UUID id,
            Workspace workspace,
            String name,
            String url,
            MonitorHttpMethod httpMethod,
            int intervalSeconds,
            int timeoutMs,
            int expectedStatus,
            int failureThreshold
    ) {
        this.id = id;
        this.workspace = workspace;
        this.name = name;
        this.url = url;
        this.httpMethod = httpMethod;
        this.intervalSeconds = intervalSeconds;
        this.timeoutMs = timeoutMs;
        this.expectedStatus = expectedStatus;
        this.failureThreshold = failureThreshold;
        this.consecutiveFailures = 0;
        this.currentStatus = MonitorStatus.UNKNOWN;
        this.paused = false;
        this.nextCheckAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.nextCheckAt == null) {
            this.nextCheckAt = now;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public MonitorHttpMethod getHttpMethod() {
        return httpMethod;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getExpectedStatus() {
        return expectedStatus;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public MonitorStatus getCurrentStatus() {
        return currentStatus;
    }

    public boolean isPaused() {
        return paused;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Integer getLastLatencyMs() {
        return lastLatencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            this.nextCheckAt = Instant.now();
        }
    }

    public void recordCheckResult(MonitorStatus checkStatus, int latencyMs, Instant checkedAt) {
        this.lastCheckedAt = checkedAt;
        this.lastLatencyMs = latencyMs;
        this.nextCheckAt = checkedAt.plusSeconds(this.intervalSeconds);
        if (checkStatus == MonitorStatus.UP) {
            this.consecutiveFailures = 0;
            this.currentStatus = MonitorStatus.UP;
        } else {
            this.consecutiveFailures += 1;
            this.currentStatus = MonitorStatus.DOWN;
        }
    }
}
