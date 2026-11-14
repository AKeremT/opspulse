package com.opspulse.alert.service;

import com.opspulse.alert.domain.AlertChannel;
import com.opspulse.alert.domain.AlertChannelType;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.common.exception.BadRequestException;
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
 * after incident state transitions commit.
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

    public void sendIncidentAlert(PendingWebhookAlert alert) {
        List<AlertChannel> channels = alertChannelRepository
                .findAllByWorkspaceIdAndChannelTypeAndEnabledTrue(alert.workspaceId(), AlertChannelType.WEBHOOK);
        if (channels.isEmpty()) {
            return;
        }

        String humanMessage = String.format(
                "[OpsPulse] %s — %s (%s): %s",
                alert.eventType(),
                alert.monitorName(),
                alert.monitorUrl(),
                alert.summary() != null ? alert.summary() : alert.status()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", alert.eventType());
        payload.put("monitorId", alert.monitorId());
        payload.put("monitorName", alert.monitorName());
        payload.put("monitorUrl", alert.monitorUrl());
        payload.put("incidentId", alert.incidentId());
        payload.put("status", alert.status());
        payload.put("summary", alert.summary());
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

    public record PendingWebhookAlert(
            UUID workspaceId,
            String eventType,
            UUID incidentId,
            UUID monitorId,
            String monitorName,
            String monitorUrl,
            String status,
            String summary
    ) {
    }
}
