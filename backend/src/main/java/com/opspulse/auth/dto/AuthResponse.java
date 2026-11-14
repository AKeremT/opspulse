package com.opspulse.auth.dto;

import com.opspulse.workspace.dto.WorkspaceResponse;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInMs,
        UserSummaryResponse user,
        WorkspaceResponse defaultWorkspace
) {
}
