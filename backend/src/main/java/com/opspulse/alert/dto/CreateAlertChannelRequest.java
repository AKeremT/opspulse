package com.opspulse.alert.dto;

import com.opspulse.alert.domain.AlertChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAlertChannelRequest(
        @NotBlank(message = "Channel name is required")
        @Size(min = 2, max = 120, message = "Channel name must be between 2 and 120 characters")
        String name,

        AlertChannelType channelType,

        @NotBlank(message = "Target URL is required")
        @Size(max = 2048, message = "Target URL must not exceed 2048 characters")
        String targetUrl
) {
}
