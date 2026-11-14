-- V2: Initialize monitors, monitor checks, incidents, and alert channels

CREATE TABLE monitors (
    id                   UUID          PRIMARY KEY,
    workspace_id         UUID          NOT NULL,
    name                 VARCHAR(120)  NOT NULL,
    url                  VARCHAR(2048) NOT NULL,
    http_method          VARCHAR(10)   NOT NULL DEFAULT 'GET',
    interval_seconds     INTEGER       NOT NULL DEFAULT 60,
    timeout_ms           INTEGER       NOT NULL DEFAULT 5000,
    expected_status      INTEGER       NOT NULL DEFAULT 200,
    failure_threshold    INTEGER       NOT NULL DEFAULT 3,
    consecutive_failures INTEGER       NOT NULL DEFAULT 0,
    current_status       VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',
    paused               BOOLEAN       NOT NULL DEFAULT FALSE,
    next_check_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_checked_at      TIMESTAMPTZ,
    last_latency_ms      INTEGER,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_monitors_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT chk_monitors_http_method CHECK (http_method IN ('GET', 'HEAD', 'POST')),
    CONSTRAINT chk_monitors_interval_seconds CHECK (interval_seconds >= 10),
    CONSTRAINT chk_monitors_timeout_ms CHECK (timeout_ms BETWEEN 500 AND 30000),
    CONSTRAINT chk_monitors_expected_status CHECK (expected_status BETWEEN 100 AND 599),
    CONSTRAINT chk_monitors_failure_threshold CHECK (failure_threshold >= 1),
    CONSTRAINT chk_monitors_consecutive_failures CHECK (consecutive_failures >= 0),
    CONSTRAINT chk_monitors_current_status CHECK (current_status IN ('UNKNOWN', 'UP', 'DOWN'))
);

CREATE TABLE monitor_checks (
    id            UUID          PRIMARY KEY,
    monitor_id    UUID          NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    status_code   INTEGER,
    latency_ms    INTEGER,
    error_message VARCHAR(1000),
    checked_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_monitor_checks_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE,
    CONSTRAINT chk_monitor_checks_status CHECK (status IN ('UP', 'DOWN'))
);

CREATE TABLE incidents (
    id              UUID         PRIMARY KEY,
    monitor_id      UUID         NOT NULL,
    workspace_id    UUID         NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    summary         VARCHAR(500),
    CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE,
    CONSTRAINT fk_incidents_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT chk_incidents_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'))
);

CREATE TABLE alert_channels (
    id           UUID          PRIMARY KEY,
    workspace_id UUID          NOT NULL,
    name         VARCHAR(120)  NOT NULL,
    channel_type VARCHAR(32)   NOT NULL DEFAULT 'WEBHOOK',
    target_url   VARCHAR(2048) NOT NULL,
    enabled      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_alert_channels_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT chk_alert_channels_type CHECK (channel_type IN ('WEBHOOK'))
);

CREATE INDEX idx_monitors_workspace_id ON monitors (workspace_id);
CREATE INDEX idx_monitors_due_checks ON monitors (paused, next_check_at);
CREATE INDEX idx_monitor_checks_monitor_checked_at ON monitor_checks (monitor_id, checked_at DESC);
CREATE INDEX idx_incidents_workspace_status ON incidents (workspace_id, status);
CREATE INDEX idx_incidents_monitor_id ON incidents (monitor_id);
CREATE INDEX idx_alert_channels_workspace_id ON alert_channels (workspace_id);
