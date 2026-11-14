package com.opspulse.monitor.worker;

import com.opspulse.common.exception.BadRequestException;
import com.opspulse.monitor.domain.MonitorHttpMethod;
import com.opspulse.monitor.domain.MonitorStatus;
import com.opspulse.monitor.service.UrlSafetyValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Executes single HTTP/HTTPS health check probes using {@link HttpClient}.
 */
@Component
public class MonitorCheckExecutor {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final UrlSafetyValidator urlSafetyValidator;
    private final HttpClient httpClient;

    public MonitorCheckExecutor(UrlSafetyValidator urlSafetyValidator) {
        this.urlSafetyValidator = urlSafetyValidator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ProbeOutcome execute(MonitorProbeTarget target) {
        long startNanos = System.nanoTime();
        Instant checkedAt = Instant.now();

        try {
            String safeUrl = urlSafetyValidator.validateAndNormalize(target.url());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(safeUrl))
                    .timeout(Duration.ofMillis(target.timeoutMs()))
                    .method(target.httpMethod().name(), HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "OpsPulse-Monitor/1.0")
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int latencyMs = elapsedMillis(startNanos);
            int statusCode = response.statusCode();

            if (statusCode == target.expectedStatus()) {
                return new ProbeOutcome(target.monitorId(), MonitorStatus.UP, statusCode, latencyMs, null, checkedAt);
            }
            return new ProbeOutcome(
                    target.monitorId(),
                    MonitorStatus.DOWN,
                    statusCode,
                    latencyMs,
                    truncate("Expected HTTP " + target.expectedStatus() + " but received " + statusCode),
                    checkedAt
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeOutcome(
                    target.monitorId(),
                    MonitorStatus.DOWN,
                    null,
                    elapsedMillis(startNanos),
                    truncate("Check interrupted: " + ex.getMessage()),
                    checkedAt
            );
        } catch (BadRequestException | IOException | IllegalArgumentException ex) {
            return new ProbeOutcome(
                    target.monitorId(),
                    MonitorStatus.DOWN,
                    null,
                    elapsedMillis(startNanos),
                    truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()),
                    checkedAt
            );
        }
    }

    private int elapsedMillis(long startNanos) {
        return (int) Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Check failed";
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    public record MonitorProbeTarget(
            UUID monitorId,
            String url,
            MonitorHttpMethod httpMethod,
            int timeoutMs,
            int expectedStatus
    ) {
    }

    public record ProbeOutcome(
            UUID monitorId,
            MonitorStatus status,
            Integer statusCode,
            int latencyMs,
            String errorMessage,
            Instant checkedAt
    ) {
    }
}
