package com.opspulse.alert.controller;

import com.opspulse.alert.dto.AlertChannelResponse;
import com.opspulse.alert.dto.CreateAlertChannelRequest;
import com.opspulse.alert.service.AlertChannelService;
import com.opspulse.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for managing workspace alert notification channels.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/alert-channels")
public class AlertChannelController {

    private final AlertChannelService alertChannelService;

    public AlertChannelController(AlertChannelService alertChannelService) {
        this.alertChannelService = alertChannelService;
    }

    @GetMapping
    public ResponseEntity<List<AlertChannelResponse>> listChannels(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(alertChannelService.listChannels(workspaceId, principal.getId()));
    }

    @PostMapping
    public ResponseEntity<AlertChannelResponse> createChannel(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateAlertChannelRequest request
    ) {
        AlertChannelResponse created = alertChannelService.createChannel(workspaceId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{channelId}")
    public ResponseEntity<Void> deleteChannel(
            @PathVariable UUID workspaceId,
            @PathVariable UUID channelId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        alertChannelService.deleteChannel(workspaceId, channelId, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
