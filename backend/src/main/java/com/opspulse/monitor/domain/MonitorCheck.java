package com.opspulse.monitor.domain;

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
@Table(name = "monitor_checks")
public class MonitorCheck {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MonitorStatus status;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected MonitorCheck() {
    }

    public MonitorCheck(
            UUID id,
            Monitor monitor,
            MonitorStatus status,
            Integer statusCode,
            Integer latencyMs,
            String errorMessage
    ) {
        this.id = id;
        this.monitor = monitor;
        this.status = status;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.errorMessage = errorMessage;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.checkedAt == null) {
            this.checkedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public MonitorStatus getStatus() {
        return status;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
