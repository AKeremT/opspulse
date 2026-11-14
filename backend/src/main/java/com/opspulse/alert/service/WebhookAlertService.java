package com.opspulse.alert.service;

import com.opspulse.alert.domain.AlertChannel;
import com.opspulse.alert.domain.AlertChannelType;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.common.exception.BadRequestException;
import com.opspulse.incident.domain.Incident;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.service.UrlSafetyValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches outbound webhook notifications asynchronously on virtual threads
 * when incidents are opened or resolved.
 */
@Service
public class WebhookAlertService {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertService.class);
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(5);

    private final AlertChannelRepository alertChannelRepository;
    private final UrlSafetyValidator urlSafetyValidator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookAlertService(
            AlertChannelRepository alertChannelRepository,
            UrlSafetyValidator urlSafetyValidator,
            ObjectMapper objectMapper
    ) {
        this.alertChannelRepository = alertChannelRepository;
        this.urlSafetyValidator = urlSafetyValidator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(WEBHOOK_TIMEOUT)
                .build();
    }

    public void sendIncidentAlert(UUID workspaceId, String eventType, Incident incident, Monitor monitor) {
        List<AlertChannel> channels = alertChannelRepository
                .findAllByWorkspaceIdAndChannelTypeAndEnabledTrue(workspaceId, AlertChannelType.WEBHOOK);
        if (channels.isEmpty()) {
            return;
        }

        String humanMessage = String.format(
                "[OpsPulse] %s — %s (%s): %s",
                eventType,
                monitor.getName(),
                monitor.getUrl(),
                incident.getSummary() != null ? incident.getSummary() : incident.getStatus().name()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventType);
        payload.put("monitorId", monitor.getId());
        payload.put("monitorName", monitor.getName());
        payload.put("monitorUrl", monitor.getUrl());
        payload.put("incidentId", incident.getId());
        payload.put("status", incident.getStatus().name());
        payload.put("summary", incident.getSummary());
        payload.put("timestamp", Instant.now().toString());
        payload.put("content", humanMessage);
        payload.put("text", humanMessage);

        String jsonBody = serializePayload(payload);
        if (jsonBody == null) {
            return;
        }

        for (AlertChannel channel : channels) {
            String targetUrl = channel.getTargetUrl();
            UUID channelId = channel.getId();
            Thread.ofVirtual().start(() -> deliverWebhook(channelId, targetUrl, jsonBody));
        }
    }

    private void deliverWebhook(UUID channelId, String targetUrl, String jsonBody) {
        try {
            String safeUrl = urlSafetyValidator.validateAndNormalize(targetUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(safeUrl))
                    .timeout(WEBHOOK_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "OpsPulse-Webhook/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                log.warn("Webhook channel {} returned HTTP {}", channelId, response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Webhook delivery interrupted for channel {}", channelId);
        } catch (BadRequestException | IOException | IllegalArgumentException ex) {
            log.warn("Webhook delivery failed for channel {}: {}", channelId, ex.getMessage());
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.error("Failed to serialize webhook payload", ex);
            return null;
        }
    }
}
