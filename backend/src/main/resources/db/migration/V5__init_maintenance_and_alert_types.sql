-- V5: Add SLACK/EMAIL alert channel types and maintenance windows table

ALTER TABLE alert_channels
    DROP CONSTRAINT chk_alert_channels_type;

ALTER TABLE alert_channels
    ADD CONSTRAINT chk_alert_channels_type CHECK (channel_type IN ('WEBHOOK', 'SLACK', 'EMAIL'));

CREATE TABLE maintenance_windows (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    title        VARCHAR(160) NOT NULL,
    description  VARCHAR(500),
    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_maintenance_windows_range CHECK (ends_at > starts_at)
);

CREATE INDEX idx_maintenance_windows_workspace_time
    ON maintenance_windows (workspace_id, starts_at, ends_at);
