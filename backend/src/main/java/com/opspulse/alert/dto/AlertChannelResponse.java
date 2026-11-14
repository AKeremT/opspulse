package com.opspulse.alert.dto;

import com.opspulse.alert.domain.AlertChannelType;
import java.time.Instant;
import java.util.UUID;

public record AlertChannelResponse(
        UUID id,
        UUID workspaceId,
        String name,
        AlertChannelType channelType,
        String targetUrl,
        boolean enabled,
        Instant createdAt
) {
}
