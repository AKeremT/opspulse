package com.opspulse.workspace.dto;

import com.opspulse.workspace.domain.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String name,
        String slug,
        WorkspaceRole role,
        Instant createdAt
) {
}
