package com.opspulse.alert.service;

import com.opspulse.alert.service.WebhookAlertService.PendingWebhookAlert;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Dispatches formatted incident notification emails when email alerting is enabled
 * and a {@link JavaMailSender} bean is available.
 */
@Service
public class EmailAlertService {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean emailEnabled;
    private final String fromAddress;

    public EmailAlertService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.alert.email.enabled:false}") boolean emailEnabled,
            @Value("${app.alert.email.from:noreply@opspulse.local}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.emailEnabled = emailEnabled;
        this.fromAddress = fromAddress;
    }

    public void sendIncidentEmail(String recipient, PendingWebhookAlert alert) {
        if (!emailEnabled) {
            log.debug("Email alerting is disabled; skipping alert email to {}", recipient);
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("Email alerting is enabled but JavaMailSender is unavailable; skipping alert email to {}", recipient);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(recipient);
            String safeEventType = sanitizeHeaderValue(alert.eventType());
            String safeMonitorName = sanitizeHeaderValue(alert.monitorName());
            message.setSubject(String.format("[OpsPulse] %s — %s", safeEventType, safeMonitorName));
            message.setText(buildBody(alert));
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Failed to send incident alert email to {}: {}", recipient, ex.getMessage());
        }
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String buildBody(PendingWebhookAlert alert) {
        String summary = alert.summary() != null && !alert.summary().isBlank()
                ? alert.summary()
                : alert.status();
        return String.format(
                """
                OpsPulse Incident Notification

                Event:       %s
                Monitor:     %s (%s)
                Status:      %s
                Summary:     %s
                Incident ID: %s
                Timestamp:   %s
                """,
                alert.eventType(),
                alert.monitorName(),
                alert.monitorUrl(),
                alert.status(),
                summary,
                alert.incidentId(),
                Instant.now()
        );
    }
}
