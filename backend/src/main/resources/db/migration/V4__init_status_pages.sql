-- V4: Initialize public status pages

CREATE TABLE status_pages (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL UNIQUE REFERENCES workspaces (id) ON DELETE CASCADE,
    slug         VARCHAR(120) NOT NULL UNIQUE,
    title        VARCHAR(160) NOT NULL,
    description  VARCHAR(500),
    is_public    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_status_pages_slug ON status_pages (slug);
