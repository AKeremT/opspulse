package com.opspulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.auth.domain.User;
import com.opspulse.auth.repository.RefreshTokenRepository;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.incident.repository.IncidentRepository;
import com.opspulse.maintenance.repository.MaintenanceWindowRepository;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.monitor.worker.MonitorCheckScheduler;
import com.opspulse.statuspage.repository.StatusPageRepository;
import com.opspulse.workspace.domain.Workspace;
import com.opspulse.workspace.domain.WorkspaceMember;
import com.opspulse.workspace.domain.WorkspaceRole;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end integration tests for Sprint 4 features:
 * Slack/Email alert channels, maintenance windows CRUD & RBAC,
 * incident suppression during active maintenance, and public status
 * maintenance window exposure with Redis cache eviction.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Sprint4AlertAndMaintenanceIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "opspulse:public-status:v1:";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MonitorCheckScheduler monitorCheckScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MaintenanceWindowRepository maintenanceWindowRepository;

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
    private HttpServer targetServer;
    private HttpServer slackServer;
    private AtomicInteger targetStatusCode;
    private BlockingQueue<String> slackPayloads;

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        maintenanceWindowRepository.deleteAllInBatch();
        statusPageRepository.deleteAllInBatch();
        incidentRepository.deleteAllInBatch();
        monitorCheckRepository.deleteAllInBatch();
        alertChannelRepository.deleteAllInBatch();
        monitorRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        workspaceMemberRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        Set<String> cacheKeys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }

        targetStatusCode = new AtomicInteger(200);
        slackPayloads = new LinkedBlockingQueue<>();

        targetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetServer.createContext("/health", exchange -> {
            int code = targetStatusCode.get();
            byte[] body = (code == 200 ? "ok" : "error").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        targetServer.start();

        slackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slackServer.createContext("/slack-webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            slackPayloads.offer(body);
            byte[] ack = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ack.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ack);
            }
        });
        slackServer.start();
    }

    @AfterEach
    void tearDown() {
        if (targetServer != null) {
            targetServer.stop(0);
        }
        if (slackServer != null) {
            slackServer.stop(0);
        }
    }

    @Test
    void slackChannelDispatchesBlockKitPayloadAndEmailChannelValidatesFormat() throws Exception {
        AuthContext owner = registerUser("alerts-owner@opspulse.io", "StrongPassword123!", "Alerts Owner");

        // Invalid EMAIL channel target is rejected with 400 Bad Request
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/alert-channels", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Invalid Email",
                                  "channelType": "EMAIL",
                                  "targetUrl": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Valid EMAIL channel is accepted without SSRF URL check
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/alert-channels", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Oncall Email",
                                  "channelType": "EMAIL",
                                  "targetUrl": "oncall@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelType").value("EMAIL"))
                .andExpect(jsonPath("$.targetUrl").value("oncall@example.com"))
                .andExpect(jsonPath("$.enabled").value(true));

        // Create SLACK channel pointing to embedded HttpServer
        String slackUrl = "http://127.0.0.1:" + slackServer.getAddress().getPort() + "/slack-webhook";
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/alert-channels", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Ops Slack",
                                  "channelType": "SLACK",
                                  "targetUrl": "%s"
                                }
                                """.formatted(slackUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelType").value("SLACK"))
                .andExpect(jsonPath("$.targetUrl").value(slackUrl))
                .andExpect(jsonPath("$.enabled").value(true));

        // Create a failing monitor (failureThreshold = 1) to trigger incident.opened
        UUID monitorId = createMonitor(owner, "Billing API", 1);
        targetStatusCode.set(500);

        int executed = monitorCheckScheduler.runDueChecks();
        assertThat(executed).isEqualTo(1);

        // Verify incident opened
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/incidents", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].monitorId").value(monitorId.toString()));

        // Verify Slack Block Kit JSON payload received
        String slackBody = slackPayloads.poll(5, TimeUnit.SECONDS);
        assertThat(slackBody).isNotNull();

        List<Map<String, Object>> blocks = JsonPath.read(slackBody, "$.blocks");
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(blocks.stream().map(b -> (String) b.get("type")))
                .contains("header", "section");
        assertThat(JsonPath.<String>read(slackBody, "$.blocks[0].type")).isEqualTo("header");
        assertThat(JsonPath.<String>read(slackBody, "$.blocks[0].text.text")).contains("incident.opened");
        assertThat(JsonPath.<String>read(slackBody, "$.blocks[1].type")).isEqualTo("section");
    }

    @Test
    void maintenanceWindowCrudValidationAndRbac() throws Exception {
        AuthContext owner = registerUser("maint-owner@opspulse.io", "StrongPassword123!", "Maint Owner");
        AuthContext member = registerUser("maint-member@opspulse.io", "StrongPassword123!", "Maint Member");
        AuthContext viewer = registerUser("maint-viewer@opspulse.io", "StrongPassword123!", "Maint Viewer");

        Workspace workspace = workspaceRepository.findById(owner.workspaceId()).orElseThrow();
        addWorkspaceMember(workspace, member.userId(), WorkspaceRole.MEMBER);
        addWorkspaceMember(workspace, viewer.userId(), WorkspaceRole.VIEWER);

        Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant end = start.plus(2, ChronoUnit.HOURS);

        String validPayload = """
                {
                  "title": "Database Upgrade",
                  "description": "Primary PostgreSQL minor version upgrade",
                  "startsAt": "%s",
                  "endsAt": "%s"
                }
                """.formatted(start, end);

        // VIEWER receives 403 Forbidden on POST
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // MEMBER rejects endsAt <= startsAt with 400 Bad Request
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Invalid Window",
                                  "description": "End equals start",
                                  "startsAt": "%s",
                                  "endsAt": "%s"
                                }
                                """.formatted(start, start)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // MEMBER can create a valid maintenance window
        MvcResult createResult = mockMvc.perform(
                        post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                                .header("Authorization", "Bearer " + member.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.title").value("Database Upgrade"))
                .andExpect(jsonPath("$.active").value(false))
                .andReturn();

        String windowId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // VIEWER can list maintenance windows (GET)
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(windowId))
                .andExpect(jsonPath("$[0].title").value("Database Upgrade"));

        // VIEWER receives 403 Forbidden on DELETE
        mockMvc.perform(delete("/api/v1/workspaces/{workspaceId}/maintenance-windows/{windowId}",
                        owner.workspaceId(), windowId)
                        .header("Authorization", "Bearer " + viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // MEMBER can delete the maintenance window
        mockMvc.perform(delete("/api/v1/workspaces/{workspaceId}/maintenance-windows/{windowId}",
                        owner.workspaceId(), windowId)
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isNoContent());

        // Verify list is now empty
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void activeMaintenanceWindowSuppressesIncidentCreationOnFailingChecks() throws Exception {
        AuthContext owner = registerUser("suppress-owner@opspulse.io", "StrongPassword123!", "Suppress Owner");

        // Create an active maintenance window covering now
        Instant now = Instant.now();
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Active Network Maintenance",
                                  "description": "Router firmware rollout",
                                  "startsAt": "%s",
                                  "endsAt": "%s"
                                }
                                """.formatted(now.minus(10, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));

        UUID monitorId = createMonitor(owner, "Core Payments", 2);
        targetStatusCode.set(500);

        // Run two consecutive failing checks so consecutiveFailures >= failureThreshold (2)
        assertThat(monitorCheckScheduler.runDueChecks()).isEqualTo(1);
        markMonitorDueNow(monitorId);
        assertThat(monitorCheckScheduler.runDueChecks()).isEqualTo(1);

        // Verify MonitorCheck records DOWN
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors/{monitorId}/checks",
                        owner.workspaceId(), monitorId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("DOWN"))
                .andExpect(jsonPath("$[0].statusCode").value(500));

        // Verify monitor consecutiveFailures reached threshold
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentStatus").value("DOWN"))
                .andExpect(jsonPath("$[0].consecutiveFailures").value(2));

        // Verify NO incident was opened during active maintenance
        Integer incidentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incidents WHERE monitor_id = ?",
                Integer.class,
                monitorId
        );
        assertThat(incidentCount).isZero();
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/incidents", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void creatingMaintenanceWindowEvictsPublicStatusCacheAndExposesActiveAndUpcomingWindows() throws Exception {
        AuthContext owner = registerUser("public-maint@opspulse.io", "StrongPassword123!", "Public Maint Owner");

        String slug = "maint-public-status";
        mockMvc.perform(put("/api/v1/workspaces/{workspaceId}/status-page", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "%s",
                                  "title": "Acme Status",
                                  "description": "Public status with maintenance",
                                  "isPublic": true
                                }
                                """.formatted(slug)))
                .andExpect(status().isOk());

        // Populate Redis cache with initial empty maintenanceWindows
        String cacheKey = CACHE_KEY_PREFIX + slug;
        mockMvc.perform(get("/api/v1/public/status/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceWindows", hasSize(0)));
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();

        // Create an active maintenance window -> evicts cache
        Instant now = Instant.now();
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Active Cache Evict Window",
                                  "description": "Ongoing DB maintenance",
                                  "startsAt": "%s",
                                  "endsAt": "%s"
                                }
                                """.formatted(now.minus(5, ChronoUnit.MINUTES), now.plus(25, ChronoUnit.MINUTES))))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // Create an upcoming maintenance window
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/maintenance-windows", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Upcoming Network Upgrade",
                                  "description": "Scheduled next hour",
                                  "startsAt": "%s",
                                  "endsAt": "%s"
                                }
                                """.formatted(now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // Public status endpoint returns both windows with accurate active flags and no created_by/user leaks
        MvcResult publicResult = mockMvc.perform(get("/api/v1/public/status/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceWindows", hasSize(2)))
                .andExpect(jsonPath("$.maintenanceWindows[0].title").value("Active Cache Evict Window"))
                .andExpect(jsonPath("$.maintenanceWindows[0].active").value(true))
                .andExpect(jsonPath("$.maintenanceWindows[1].title").value("Upcoming Network Upgrade"))
                .andExpect(jsonPath("$.maintenanceWindows[1].active").value(false))
                .andReturn();

        String publicBody = publicResult.getResponse().getContentAsString();
        assertThat(publicBody)
                .doesNotContain("createdBy")
                .doesNotContain("created_by")
                .doesNotContain("public-maint@opspulse.io")
                .doesNotContain(owner.userId().toString());
    }

    private UUID createMonitor(AuthContext owner, String name, int failureThreshold) throws Exception {
        String targetUrl = "http://127.0.0.1:" + targetServer.getAddress().getPort() + "/health";
        String payload = """
                {
                  "name": "%s",
                  "url": "%s",
                  "httpMethod": "GET",
                  "intervalSeconds": 10,
                  "timeoutMs": 3000,
                  "expectedStatus": 200,
                  "failureThreshold": %d
                }
                """.formatted(name, targetUrl, failureThreshold);

        MvcResult result = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void markMonitorDueNow(UUID monitorId) {
        jdbcTemplate.update(
                "UPDATE monitors SET next_check_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                monitorId
        );
    }

    private void addWorkspaceMember(Workspace workspace, UUID userId, WorkspaceRole role) {
        User user = userRepository.findById(userId).orElseThrow();
        workspaceMemberRepository.save(new WorkspaceMember(UUID.randomUUID(), workspace, user, role));
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
