package com.opspulse.alert.service;

import com.opspulse.alert.domain.AlertChannel;
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
    private final EmailAlertService emailAlertService;
    private final HttpClient httpClient;

    public WebhookAlertService(
            AlertChannelRepository alertChannelRepository,
            UrlSafetyValidator urlSafetyValidator,
            ObjectMapper objectMapper,
            EmailAlertService emailAlertService
    ) {
        this.alertChannelRepository = alertChannelRepository;
        this.urlSafetyValidator = urlSafetyValidator;
        this.objectMapper = objectMapper;
        this.emailAlertService = emailAlertService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(WEBHOOK_TIMEOUT)
                .build();
    }

    public void sendIncidentAlert(PendingWebhookAlert alert) {
        List<AlertChannel> channels = alertChannelRepository.findAllByWorkspaceIdAndEnabledTrue(alert.workspaceId());
        if (channels.isEmpty()) {
            return;
        }

        String webhookJsonBody = null;
        String slackJsonBody = null;

        for (AlertChannel channel : channels) {
            UUID channelId = channel.getId();
            String target = channel.getTargetUrl();
            switch (channel.getChannelType()) {
                case WEBHOOK -> {
                    if (webhookJsonBody == null) {
                        webhookJsonBody = serializePayload(buildWebhookPayload(alert));
                    }
                    if (webhookJsonBody != null) {
                        String body = webhookJsonBody;
                        Thread.ofVirtual().start(() -> deliverWebhook(channelId, target, body));
                    }
                }
                case SLACK -> {
                    if (slackJsonBody == null) {
                        slackJsonBody = serializePayload(buildSlackBlockKitPayload(alert));
                    }
                    if (slackJsonBody != null) {
                        String body = slackJsonBody;
                        Thread.ofVirtual().start(() -> deliverWebhook(channelId, target, body));
                    }
                }
                case EMAIL -> Thread.ofVirtual().start(() -> emailAlertService.sendIncidentEmail(target, alert));
            }
        }
    }

    private Map<String, Object> buildWebhookPayload(PendingWebhookAlert alert) {
        String humanMessage = formatHumanMessage(alert);
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
        return payload;
    }

    private Map<String, Object> buildSlackBlockKitPayload(PendingWebhookAlert alert) {
        String summaryText = alert.summary() != null && !alert.summary().isBlank()
                ? alert.summary()
                : alert.status();

        Map<String, Object> headerBlock = Map.of(
                "type", "header",
                "text", Map.of(
                        "type", "plain_text",
                        "text", String.format("[OpsPulse] %s", alert.eventType())
                )
        );

        List<Map<String, Object>> fields = List.of(
                mrkdwnField("*Monitor:*\n" + alert.monitorName() + " (" + alert.monitorUrl() + ")"),
                mrkdwnField("*Status:*\n" + alert.status()),
                mrkdwnField("*Summary:*\n" + summaryText),
                mrkdwnField("*Incident ID:*\n" + alert.incidentId())
        );

        Map<String, Object> sectionBlock = Map.of(
                "type", "section",
                "fields", fields
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", formatHumanMessage(alert));
        payload.put("blocks", List.of(headerBlock, sectionBlock));
        return payload;
    }

    private Map<String, Object> mrkdwnField(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    private String formatHumanMessage(PendingWebhookAlert alert) {
        return String.format(
                "[OpsPulse] %s — %s (%s): %s",
                alert.eventType(),
                alert.monitorName(),
                alert.monitorUrl(),
                alert.summary() != null ? alert.summary() : alert.status()
        );
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
