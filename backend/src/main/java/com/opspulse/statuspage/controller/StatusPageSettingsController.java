package com.opspulse.statuspage.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.statuspage.dto.StatusPageSettingsResponse;
import com.opspulse.statuspage.dto.UpdateStatusPageRequest;
import com.opspulse.statuspage.service.StatusPageService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated endpoints for viewing and configuring a workspace's public status page.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/status-page")
public class StatusPageSettingsController {

    private final StatusPageService statusPageService;

    public StatusPageSettingsController(StatusPageService statusPageService) {
        this.statusPageService = statusPageService;
    }

    @GetMapping
    public ResponseEntity<StatusPageSettingsResponse> getSettings(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(statusPageService.getSettings(workspaceId, principal.getId()));
    }

    @PutMapping
    public ResponseEntity<StatusPageSettingsResponse> updateSettings(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateStatusPageRequest request
    ) {
        return ResponseEntity.ok(
                statusPageService.updateSettings(workspaceId, principal.getId(), request)
        );
    }
}
