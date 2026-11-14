package com.opspulse.workspace.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.workspace.dto.CreateWorkspaceRequest;
import com.opspulse.workspace.dto.WorkspaceResponse;
import com.opspulse.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for listing and creating workspaces.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceResponse>> listWorkspaces(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(workspaceService.listUserWorkspaces(principal.getId()));
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> createWorkspace(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        WorkspaceResponse created = workspaceService.createWorkspace(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
