package com.opspulse.incident.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.incident.dto.IncidentResponse;
import com.opspulse.incident.service.IncidentService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for listing and acknowledging workspace incidents.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public ResponseEntity<List<IncidentResponse>> listIncidents(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(incidentService.listIncidents(workspaceId, principal.getId()));
    }

    @PostMapping("/{incidentId}/acknowledge")
    public ResponseEntity<IncidentResponse> acknowledgeIncident(
            @PathVariable UUID workspaceId,
            @PathVariable UUID incidentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(incidentService.acknowledgeIncident(workspaceId, incidentId, principal.getId()));
    }
}
