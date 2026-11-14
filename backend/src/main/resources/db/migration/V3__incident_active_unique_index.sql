-- V3: Enforce at most one active (OPEN or ACKNOWLEDGED) incident per monitor
CREATE UNIQUE INDEX uq_incidents_active_monitor
    ON incidents (monitor_id)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');
