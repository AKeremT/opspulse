package com.opspulse.monitor.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.monitor.dto.CreateMonitorRequest;
import com.opspulse.monitor.dto.MonitorCheckResponse;
import com.opspulse.monitor.dto.MonitorResponse;
import com.opspulse.monitor.dto.PauseMonitorRequest;
import com.opspulse.monitor.service.MonitorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for managing workspace uptime monitors and viewing check history.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/monitors")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping
    public ResponseEntity<List<MonitorResponse>> listMonitors(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(monitorService.listMonitors(workspaceId, principal.getId()));
    }

    @PostMapping
    public ResponseEntity<MonitorResponse> createMonitor(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateMonitorRequest request
    ) {
        MonitorResponse created = monitorService.createMonitor(workspaceId, principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{monitorId}/pause")
    public ResponseEntity<MonitorResponse> pauseMonitor(
            @PathVariable UUID workspaceId,
            @PathVariable UUID monitorId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) PauseMonitorRequest request
    ) {
        Boolean paused = request != null ? request.paused() : null;
        return ResponseEntity.ok(monitorService.setMonitorPaused(workspaceId, monitorId, principal.getId(), paused));
    }

    @DeleteMapping("/{monitorId}")
    public ResponseEntity<Void> deleteMonitor(
            @PathVariable UUID workspaceId,
            @PathVariable UUID monitorId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        monitorService.deleteMonitor(workspaceId, monitorId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{monitorId}/checks")
    public ResponseEntity<List<MonitorCheckResponse>> listRecentChecks(
            @PathVariable UUID workspaceId,
            @PathVariable UUID monitorId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(monitorService.listRecentChecks(workspaceId, monitorId, principal.getId()));
    }
}
