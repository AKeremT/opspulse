package com.opspulse.incident.domain;

import com.opspulse.monitor.domain.Monitor;
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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "summary", length = 500)
    private String summary;

    protected Incident() {
    }

    public Incident(
            UUID id,
            Monitor monitor,
            Workspace workspace,
            IncidentStatus status,
            Instant startedAt,
            String summary
    ) {
        this.id = id;
        this.monitor = monitor;
        this.workspace = workspace;
        this.status = status;
        this.startedAt = startedAt;
        this.summary = summary;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void acknowledge(Instant acknowledgedAt) {
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedAt = acknowledgedAt;
    }

    public void resolve(Instant resolvedAt) {
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
    }
}
