package com.opspulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.auth.domain.User;
import com.opspulse.auth.repository.RefreshTokenRepository;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.common.ratelimit.PublicRateLimitFilter;
import com.opspulse.incident.domain.Incident;
import com.opspulse.incident.domain.IncidentStatus;
import com.opspulse.incident.repository.IncidentRepository;
import com.opspulse.monitor.domain.Monitor;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.statuspage.repository.StatusPageRepository;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.domain.WorkspaceMember;
import com.opspulse.workspace.domain.WorkspaceRole;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end integration tests for public status page settings, RBAC,
 * uptime/incident aggregation, sensitive-field omission, Redis caching,
 * and Redis-backed rate limiting.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublicStatusIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "opspulse:public-status:v1:";
    private static final String RATE_LIMIT_KEY_PREFIX = "opspulse:ratelimit:public:";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PublicRateLimitFilter publicRateLimitFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private StatusPageRepository statusPageRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private MonitorCheckRepository monitorCheckRepository;

    @Autowired
    private AlertChannelRepository alertChannelRepository;

    @Autowired
    private MonitorRepository monitorRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(publicRateLimitFilter)
                .apply(springSecurity())
                .build();

        cleanDatabaseAndRedis();
    }

    @Test
    void publicStatusReturns404WhenSlugMissingOrDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/public/status/{slug}", "non-existent-slug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        AuthContext owner = registerUser("owner-404@opspulse.io", "StrongPassword123!", "Owner User");

        MvcResult settingsResult = mockMvc.perform(
                        get("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                                .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(false))
                .andReturn();

        String defaultSlug = JsonPath.read(settingsResult.getResponse().getContentAsString(), "$.slug");

        mockMvc.perform(get("/api/v1/public/status/{slug}", defaultSlug))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void statusPageSettingsRbacValidationAndDuplicateSlugConflict() throws Exception {
        AuthContext owner = registerUser("owner-rbac@opspulse.io", "StrongPassword123!", "Owner User");
        AuthContext admin = registerUser("admin-rbac@opspulse.io", "StrongPassword123!", "Admin User");
        AuthContext member = registerUser("member-rbac@opspulse.io", "StrongPassword123!", "Member User");
        AuthContext viewer = registerUser("viewer-rbac@opspulse.io", "StrongPassword123!", "Viewer User");
        AuthContext secondOwner = registerUser("second-owner@opspulse.io", "StrongPassword123!", "Second Owner");

        Workspace workspace = workspaceRepository.findById(owner.workspaceId()).orElseThrow();
        addWorkspaceMember(workspace, admin.userId(), WorkspaceRole.ADMIN);
        addWorkspaceMember(workspace, member.userId(), WorkspaceRole.MEMBER);
        addWorkspaceMember(workspace, viewer.userId(), WorkspaceRole.VIEWER);

        // VIEWER can read status-page settings (auto-initializing defaults with isPublic = false)
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(owner.workspaceId().toString()))
                .andExpect(jsonPath("$.isPublic").value(false))
                .andExpect(jsonPath("$.slug", notNullValue()));

        String validUpdatePayload = """
                {
                  "slug": "acme-status",
                  "title": "Acme System Status",
                  "description": "Live uptime for Acme services",
                  "isPublic": true
                }
                """;

        // VIEWER and MEMBER receive 403 Forbidden on PUT
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdatePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdatePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Invalid slug format triggers 400 validation error
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "Invalid Slug!",
                                  "title": "Acme Status",
                                  "description": "Test",
                                  "isPublic": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.slug", notNullValue()));

        // ADMIN can update settings
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("acme-status"))
                .andExpect(jsonPath("$.title").value("Acme System Status"))
                .andExpect(jsonPath("$.isPublic").value(true));

        // Duplicate slug across another workspace returns 409 Conflict
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", secondOwner.workspaceId())
                        .header("Authorization", "Bearer " + secondOwner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdatePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void publicStatusCalculatesUptimeBucketsIncidentsAndOmitsSensitiveFields() throws Exception {
        AuthContext owner = registerUser("owner-metrics@opspulse.io", "StrongPassword123!", "Metrics Owner");

        // Create a webhook alert channel to verify targetUrl/alert details are never leaked
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/alert-channels", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Oncall Webhook",
                                  "channelType": "WEBHOOK",
                                  "targetUrl": "https://hooks.secret.example.com/oncall-alert"
                                }
                                """))
                .andExpect(status().isCreated());

        // Create two monitors with sensitive internal URLs
        UUID apiMonitorId = createMonitor(
                owner,
                "API Gateway",
                "https://secret-internal.example.com/health"
        );
        UUID workerMonitorId = createMonitor(
                owner,
                "Worker Queue",
                "https://worker-internal.example.com/ready"
        );

        // Set API Gateway UP and Worker Queue DOWN so overallStatus is DEGRADED
        jdbcTemplate.update(
                "UPDATE monitors SET current_status = 'UP', last_latency_ms = 42, last_checked_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)),
                apiMonitorId
        );
        jdbcTemplate.update(
                "UPDATE monitors SET current_status = 'DOWN', last_latency_ms = 510, last_checked_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.MINUTES)),
                workerMonitorId
        );

        Instant now = Instant.now();
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        Instant todayTimestamp1 = now.minus(2, ChronoUnit.HOURS);
        Instant todayTimestamp2 = now.minus(1, ChronoUnit.HOURS);
        // Ensure today's checks fall on todayUtc even near midnight UTC
        if (!LocalDate.ofInstant(todayTimestamp1, ZoneOffset.UTC).equals(todayUtc)) {
            todayTimestamp1 = todayUtc.atStartOfDay(ZoneOffset.UTC).plusMinutes(5).toInstant();
            todayTimestamp2 = todayUtc.atStartOfDay(ZoneOffset.UTC).plusMinutes(10).toInstant();
        }
        Instant threeDaysAgo = todayUtc.minusDays(3).atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant fifteenDaysAgo = todayUtc.minusDays(15).atTime(12, 0).toInstant(ZoneOffset.UTC);

        // API Gateway checks:
        // 24h: 1 UP, 1 DOWN => 50.0%
        // 7d:  3 UP, 1 DOWN => 75.0%
        // 30d: 4 UP, 2 DOWN => 66.67%
        insertMonitorCheck(apiMonitorId, "UP", 200, 40, todayTimestamp1);
        insertMonitorCheck(apiMonitorId, "DOWN", 500, 120, todayTimestamp2);
        insertMonitorCheck(apiMonitorId, "UP", 200, 35, threeDaysAgo);
        insertMonitorCheck(apiMonitorId, "UP", 200, 38, threeDaysAgo.plus(1, ChronoUnit.HOURS));
        insertMonitorCheck(apiMonitorId, "UP", 200, 45, fifteenDaysAgo);
        insertMonitorCheck(apiMonitorId, "DOWN", 503, 210, fifteenDaysAgo.plus(1, ChronoUnit.HOURS));

        // Worker Queue checks: 1 DOWN today => 0.0%
        insertMonitorCheck(workerMonitorId, "DOWN", 503, 510, todayTimestamp2);

        // Persist a recent incident for Worker Queue
        Monitor workerMonitor = monitorRepository.findById(workerMonitorId).orElseThrow();
        Workspace workspace = workspaceRepository.findById(owner.workspaceId()).orElseThrow();
        incidentRepository.save(new Incident(
                UUID.randomUUID(),
                workerMonitor,
                workspace,
                IncidentStatus.OPEN,
                now.minus(30, ChronoUnit.MINUTES),
                "Worker Queue failed 3 consecutive checks"
        ));

        // Enable public status page
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "ops-status",
                                  "title": "OpsPulse Public Status",
                                  "description": "Real-time service health",
                                  "isPublic": true
                                }
                                """))
                .andExpect(status().isOk());

        // Fetch public status WITHOUT authentication
        MvcResult result = mockMvc.perform(get("/api/v1/public/status/{slug}", "ops-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ops-status"))
                .andExpect(jsonPath("$.title").value("OpsPulse Public Status"))
                .andExpect(jsonPath("$.description").value("Real-time service health"))
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.monitors", hasSize(2)))
                .andExpect(jsonPath("$.recentIncidents", hasSize(1)))
                .andExpect(jsonPath("$.recentIncidents[0].monitorName").value("Worker Queue"))
                .andExpect(jsonPath("$.recentIncidents[0].status").value("OPEN"))
                .andExpect(jsonPath("$.recentIncidents[0].summary").value("Worker Queue failed 3 consecutive checks"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Assert sensitive fields and internal values are not leaked
        assertThat(body)
                .doesNotContain("\"url\"")
                .doesNotContain("\"targetUrl\"")
                .doesNotContain("\"alert_channels\"")
                .doesNotContain("\"alertChannels\"")
                .doesNotContain("\"email\"")
                .doesNotContain("secret-internal.example.com")
                .doesNotContain("worker-internal.example.com")
                .doesNotContain("hooks.secret.example.com");

        // Verify uptime calculations and dailyUptime buckets for API Gateway
        List<Map<String, Object>> monitors = JsonPath.read(body, "$.monitors");
        Map<String, Object> apiMonitor = monitors.stream()
                .filter(m -> "API Gateway".equals(m.get("name")))
                .findFirst()
                .orElseThrow();

        assertThat(((Number) apiMonitor.get("uptime24h")).doubleValue()).isEqualTo(50.0);
        assertThat(((Number) apiMonitor.get("uptime7d")).doubleValue()).isEqualTo(75.0);
        assertThat(((Number) apiMonitor.get("uptime30d")).doubleValue()).isEqualTo(66.67);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dailyUptime = (List<Map<String, Object>>) apiMonitor.get("dailyUptime");
        assertThat(dailyUptime).hasSize(30);

        assertDailyBucket(dailyUptime, todayUtc.toString(), 50.0, 2L);
        assertDailyBucket(dailyUptime, todayUtc.minusDays(3).toString(), 100.0, 2L);
        assertDailyBucket(dailyUptime, todayUtc.minusDays(15).toString(), 50.0, 2L);
    }

    @Test
    void publicStatusIsCachedInRedisAndEvictedOnUpdate() throws Exception {
        AuthContext owner = registerUser("owner-cache@opspulse.io", "StrongPassword123!", "Cache Owner");

        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "cached-status",
                                  "title": "Initial Title",
                                  "description": "Initial Description",
                                  "isPublic": true
                                }
                                """))
                .andExpect(status().isOk());

        String cacheKey = CACHE_KEY_PREFIX + "cached-status";
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // First GET populates Redis cache
        mockMvc.perform(get("/api/v1/public/status/{slug}", "cached-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Initial Title"))
                .andExpect(jsonPath("$.description").value("Initial Description"));

        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
        assertThat(redisTemplate.opsForValue().get(cacheKey)).contains("Initial Title");

        // Mutate DB directly behind the cache
        jdbcTemplate.update(
                "UPDATE status_pages SET title = ?, description = ? WHERE slug = ?",
                "Modified Directly In DB",
                "Bypassed Cache",
                "cached-status"
        );

        // Subsequent GET serves cached payload
        mockMvc.perform(get("/api/v1/public/status/{slug}", "cached-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Initial Title"))
                .andExpect(jsonPath("$.description").value("Initial Description"));

        // PUT settings evicts Redis cache
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "cached-status",
                                  "title": "Updated Via API",
                                  "description": "Fresh Cache Value",
                                  "isPublic": true
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // Next GET fetches updated data and repopulates cache
        mockMvc.perform(get("/api/v1/public/status/{slug}", "cached-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Via API"))
                .andExpect(jsonPath("$.description").value("Fresh Cache Value"));

        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
    }

    @Nested
    @TestPropertySource(properties = "app.public-status.rate-limit-per-minute=3")
    class RateLimitTests {

        @BeforeEach
        void resetState() {
            cleanDatabaseAndRedis();
        }

        @Test
        void publicStatusEnforcesRedisRateLimitAndReturns429WithRetryAfter() throws Exception {
            AuthContext owner = registerUser("owner-ratelimit@opspulse.io", "StrongPassword123!", "RateLimit Owner");

            mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                            .header("Authorization", "Bearer " + owner.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "rate-limited-page",
                                      "title": "Rate Limited Status",
                                      "description": "Testing rate limits",
                                      "isPublic": true
                                    }
                                    """))
                    .andExpect(status().isOk());

            String clientIp = "198.51.100.42";

            // First 3 requests succeed with X-RateLimit-Limit=3 and decrementing X-RateLimit-Remaining
            for (int i = 0; i < 3; i++) {
                String expectedRemaining = String.valueOf(2 - i);
                mockMvc.perform(get("/api/v1/public/status/{slug}", "rate-limited-page")
                                .header("X-Forwarded-For", clientIp))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-RateLimit-Limit", "3"))
                        .andExpect(header().string("X-RateLimit-Remaining", expectedRemaining));
            }

            // 4th request (N+1) exceeds limit -> 429 Too Many Requests + Retry-After + ApiError JSON body
            mockMvc.perform(get("/api/v1/public/status/{slug}", "rate-limited-page")
                            .header("X-Forwarded-For", clientIp))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.error").value("Too Many Requests"))
                    .andExpect(jsonPath("$.message", notNullValue()))
                    .andExpect(jsonPath("$.path").value("/api/v1/public/status/rate-limited-page"));
        }
    }

    private void cleanDatabaseAndRedis() {
        statusPageRepository.deleteAllInBatch();
        incidentRepository.deleteAllInBatch();
        monitorCheckRepository.deleteAllInBatch();
        alertChannelRepository.deleteAllInBatch();
        monitorRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        workspaceMemberRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        deleteRedisKeysByPattern(CACHE_KEY_PREFIX + "*");
        deleteRedisKeysByPattern(RATE_LIMIT_KEY_PREFIX + "*");
    }

    private void deleteRedisKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void addWorkspaceMember(Workspace workspace, UUID userId, WorkspaceRole role) {
        User user = userRepository.findById(userId).orElseThrow();
        workspaceMemberRepository.save(new WorkspaceMember(UUID.randomUUID(), workspace, user, role));
    }

    private UUID createMonitor(AuthContext owner, String name, String url) throws Exception {
        String payload = """
                {
                  "name": "%s",
                  "url": "%s",
                  "httpMethod": "GET",
                  "intervalSeconds": 60,
                  "timeoutMs": 3000,
                  "expectedStatus": 200,
                  "failureThreshold": 3
                }
                """.formatted(name, url);

        MvcResult result = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void insertMonitorCheck(
            UUID monitorId,
            String status,
            int statusCode,
            int latencyMs,
            Instant checkedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO monitor_checks (id, monitor_id, status, status_code, latency_ms, error_message, checked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                monitorId,
                status,
                statusCode,
                latencyMs,
                "DOWN".equals(status) ? "HTTP " + statusCode : null,
                Timestamp.from(checkedAt)
        );
    }

    private void assertDailyBucket(
            List<Map<String, Object>> dailyUptime,
            String expectedDate,
            double expectedUptimePercent,
            long expectedTotalChecks
    ) {
        Map<String, Object> bucket = dailyUptime.stream()
                .filter(b -> expectedDate.equals(b.get("date")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing dailyUptime bucket for date " + expectedDate));

        assertThat(((Number) bucket.get("uptimePercent")).doubleValue()).isEqualTo(expectedUptimePercent);
        assertThat(((Number) bucket.get("totalChecks")).longValue()).isEqualTo(expectedTotalChecks);
    }

    private AuthContext registerUser(String email, String password, String fullName) throws Exception {
        String payload = """
                {
                  "email": "%s",
                  "password": "%s",
                  "fullName": "%s"
                }
                """.formatted(email, password, fullName);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        return new AuthContext(
                JsonPath.read(body, "$.accessToken"),
                UUID.fromString(JsonPath.read(body, "$.defaultWorkspace.id")),
                UUID.fromString(JsonPath.read(body, "$.user.id"))
        );
    }

    private record AuthContext(String accessToken, UUID workspaceId, UUID userId) {
    }
}
