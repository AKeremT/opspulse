package com.opspulse.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling unless explicitly disabled (e.g., in integration tests).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.monitor.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SchedulingConfig {
}
