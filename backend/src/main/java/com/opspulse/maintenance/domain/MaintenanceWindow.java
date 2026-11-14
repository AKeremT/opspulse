package com.opspulse.maintenance.domain;

import com.opspulse.workspace.domain.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Scheduled maintenance window for a workspace during which new incident creation is suppressed.
 */
@Entity
@Table(name = "maintenance_windows")
public class MaintenanceWindow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MaintenanceWindow() {
    }

    public MaintenanceWindow(
            UUID id,
            Workspace workspace,
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            UUID createdBy
    ) {
        this.id = id;
        this.workspace = workspace;
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        this.createdAt = Instant.now();
    }

    public boolean isActiveAt(Instant at) {
        return !startsAt.isAfter(at) && endsAt.isAfter(at);
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
