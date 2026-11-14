package com.opspulse.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank(message = "Workspace name is required")
        @Size(min = 2, max = 120, message = "Workspace name must be between 2 and 120 characters")
        String name,

        @Size(min = 2, max = 120, message = "Workspace slug must be between 2 and 120 characters")
        @Pattern(
                regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Slug must contain only lowercase letters, numbers, and single hyphens"
        )
        String slug
) {
}
