package com.opspulse.statuspage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for updating a workspace's public status page settings.
 */
public record UpdateStatusPageRequest(
        @NotBlank(message = "Slug is required")
        @Size(min = 2, max = 120, message = "Slug must be between 2 and 120 characters")
        @Pattern(
                regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Slug must contain only lowercase letters, numbers, and hyphens"
        )
        String slug,

        @NotBlank(message = "Title is required")
        @Size(max = 160, message = "Title must not exceed 160 characters")
        String title,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @NotNull(message = "isPublic flag is required")
        Boolean isPublic
) {
}
