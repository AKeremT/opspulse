package com.opspulse.maintenance.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.maintenance.dto.CreateMaintenanceWindowRequest;
import com.opspulse.maintenance.dto.MaintenanceWindowResponse;
import com.opspulse.maintenance.service.MaintenanceWindowService;
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
 * REST endpoints for managing workspace scheduled maintenance windows.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/maintenance-windows")
public class MaintenanceWindowController {

    private final MaintenanceWindowService maintenanceWindowService;

    public MaintenanceWindowController(MaintenanceWindowService maintenanceWindowService) {
        this.maintenanceWindowService = maintenanceWindowService;
    }

    @GetMapping
    public ResponseEntity<List<MaintenanceWindowResponse>> list(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(maintenanceWindowService.list(workspaceId, principal.getId()));
    }

    @PostMapping
    public ResponseEntity<MaintenanceWindowResponse> create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateMaintenanceWindowRequest request
    ) {
        MaintenanceWindowResponse created = maintenanceWindowService.create(
                workspaceId,
                principal.getId(),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{windowId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID workspaceId,
            @PathVariable UUID windowId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        maintenanceWindowService.delete(workspaceId, windowId, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
