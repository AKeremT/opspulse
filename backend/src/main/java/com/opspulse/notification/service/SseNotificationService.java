package com.opspulse.notification.service;

import com.opspulse.notification.dto.IncidentChangedEvent;
import com.opspulse.notification.dto.MonitorCheckedEvent;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages workspace-scoped Server-Sent Events (SSE) subscriptions and broadcasts
 * real-time monitor check and incident lifecycle events.
 */
@Service
public class SseNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationService.class);
    private static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByWorkspace =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID workspaceId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> workspaceEmitters =
                emittersByWorkspace.computeIfAbsent(workspaceId, ignored -> new CopyOnWriteArrayList<>());
        workspaceEmitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(workspaceId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            removeEmitter(workspaceId, emitter);
        });
        emitter.onError(ex -> removeEmitter(workspaceId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("workspaceId", workspaceId)));
        } catch (IOException | IllegalStateException ex) {
            removeEmitter(workspaceId, emitter);
        }

        return emitter;
    }

    public void publishMonitorChecked(UUID workspaceId, MonitorCheckedEvent event) {
        sendToWorkspace(workspaceId, "monitor.checked", event);
    }

    public void publishIncidentChanged(UUID workspaceId, IncidentChangedEvent event) {
        sendToWorkspace(workspaceId, "incident.changed", event);
    }

    private void sendToWorkspace(UUID workspaceId, String eventName, Object payload) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByWorkspace.get(workspaceId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload));
            } catch (IOException | IllegalStateException ex) {
                log.debug("Removing inactive SSE emitter for workspace {}: {}", workspaceId, ex.getMessage());
                removeEmitter(workspaceId, emitter);
            }
        }
    }

    private void removeEmitter(UUID workspaceId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByWorkspace.get(workspaceId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByWorkspace.remove(workspaceId, emitters);
        }
    }
}
