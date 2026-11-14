package com.opspulse.statuspage.dto;

import com.opspulse.statuspage.domain.StatusPage;
import java.time.Instant;
import java.util.UUID;

/**
 * Authenticated workspace status page settings response.
 */
public record StatusPageSettingsResponse(
        UUID id,
        UUID workspaceId,
        String slug,
        String title,
        String description,
        boolean isPublic,
        Instant updatedAt
) {

    public static StatusPageSettingsResponse from(StatusPage statusPage) {
        return new StatusPageSettingsResponse(
                statusPage.getId(),
                statusPage.getWorkspace().getId(),
                statusPage.getSlug(),
                statusPage.getTitle(),
                statusPage.getDescription(),
                statusPage.isPublic(),
                statusPage.getUpdatedAt()
        );
    }
}
