package com.opspulse.notification.controller;

import com.opspulse.auth.security.UserPrincipal;
import com.opspulse.notification.service.SseNotificationService;
import com.opspulse.workspace.service.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams real-time monitor and incident updates to authenticated workspace members via SSE.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/events")
public class SseController {

    private final WorkspaceAccessService workspaceAccessService;
    private final SseNotificationService sseNotificationService;

    public SseController(
            WorkspaceAccessService workspaceAccessService,
            SseNotificationService sseNotificationService
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.sseNotificationService = sseNotificationService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        workspaceAccessService.requireReadAccess(workspaceId, principal.getId());
        return sseNotificationService.subscribe(workspaceId);
    }
}
