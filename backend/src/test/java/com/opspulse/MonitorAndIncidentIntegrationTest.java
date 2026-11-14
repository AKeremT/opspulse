package com.opspulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspulse.alert.repository.AlertChannelRepository;
import com.opspulse.auth.repository.RefreshTokenRepository;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.common.exception.BadRequestException;
import com.opspulse.incident.repository.IncidentRepository;
import com.opspulse.monitor.repository.MonitorCheckRepository;
import com.opspulse.monitor.repository.MonitorRepository;
import com.opspulse.monitor.service.UrlSafetyValidator;
import com.opspulse.monitor.worker.MonitorCheckScheduler;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end integration tests for monitor CRUD, workspace isolation,
 * SSRF URL validation, health-check execution, incident lifecycle,
 * and asynchronous webhook delivery.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MonitorAndIncidentIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MonitorCheckScheduler monitorCheckScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private HttpServer webhookServer;
    private AtomicInteger targetStatusCode;
    private BlockingQueue<String> webhookPayloads;

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        incidentRepository.deleteAllInBatch();
        monitorCheckRepository.deleteAllInBatch();
        alertChannelRepository.deleteAllInBatch();
        monitorRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        workspaceMemberRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        targetStatusCode = new AtomicInteger(200);
        webhookPayloads = new LinkedBlockingQueue<>();

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

        webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            webhookPayloads.offer(body);
            byte[] ack = "received".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ack.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ack);
            }
        });
        webhookServer.start();
    }

    @AfterEach
    void tearDown() {
        if (targetServer != null) {
            targetServer.stop(0);
        }
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Test
    void monitorCrudAndWorkspaceIsolation() throws Exception {
        AuthContext owner = registerUser("owner@opspulse.io", "StrongPassword123!", "Owner User");
        AuthContext outsider = registerUser("outsider@opspulse.io", "StrongPassword123!", "Outsider User");

        String targetUrl = "http://127.0.0.1:" + targetServer.getAddress().getPort() + "/health";
        String createPayload = """
                {
                  "name": "API Health",
                  "url": "%s",
                  "httpMethod": "GET",
                  "intervalSeconds": 30,
                  "timeoutMs": 3000,
                  "expectedStatus": 200,
                  "failureThreshold": 2
                }
                """.formatted(targetUrl);

        MvcResult createResult = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("API Health"))
                .andExpect(jsonPath("$.url").value(targetUrl))
                .andExpect(jsonPath("$.currentStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.paused").value(false))
                .andReturn();

        String monitorId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // List monitors in owner's workspace
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(monitorId));

        // Non-member receives 403 Forbidden
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isForbidden());

        // Pause and resume monitor
        mockMvc.perform(patch("/api/v1/workspaces/{workspaceId}/monitors/{monitorId}/pause",
                        owner.workspaceId(), monitorId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "paused": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));

        mockMvc.perform(patch("/api/v1/workspaces/{workspaceId}/monitors/{monitorId}/pause",
                        owner.workspaceId(), monitorId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "paused": false }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));

        // Delete monitor
        mockMvc.perform(delete("/api/v1/workspaces/{workspaceId}/monitors/{monitorId}",
                        owner.workspaceId(), monitorId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void ssrfGuardBlocksPrivateAndMetadataTargetsWhenDisabled() {
        UrlSafetyValidator strictValidator = new UrlSafetyValidator(false);

        assertThatThrownBy(() -> strictValidator.validateAndNormalize("http://127.0.0.1:8080"))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> strictValidator.validateAndNormalize("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> strictValidator.validateAndNormalize("http://localhost:8080/health"))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> strictValidator.validateAndNormalize("ftp://example.com/resource"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void fullHealthCheckIncidentWebhookAndRecoveryCycle() throws Exception {
        AuthContext owner = registerUser("sre@opspulse.io", "StrongPassword123!", "SRE Lead");

        String webhookUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/webhook";
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/alert-channels", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Ops Webhook",
                                  "channelType": "WEBHOOK",
                                  "targetUrl": "%s"
                                }
                                """.formatted(webhookUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelType").value("WEBHOOK"))
                .andExpect(jsonPath("$.enabled").value(true));

        String targetUrl = "http://127.0.0.1:" + targetServer.getAddress().getPort() + "/health";
        MvcResult monitorResult = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Checkout Service",
                                  "url": "%s",
                                  "httpMethod": "GET",
                                  "intervalSeconds": 10,
                                  "timeoutMs": 3000,
                                  "expectedStatus": 200,
                                  "failureThreshold": 2
                                }
                                """.formatted(targetUrl)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID monitorId = UUID.fromString(JsonPath.read(monitorResult.getResponse().getContentAsString(), "$.id"));

        // Toggle target to 500 Internal Server Error and execute two consecutive checks
        targetStatusCode.set(500);

        int firstRun = monitorCheckScheduler.runDueChecks();
        assertThat(firstRun).isEqualTo(1);

        // No incident yet after 1 failure (failureThreshold = 2)
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/incidents", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Make monitor due again and run second failing check
        markMonitorDueNow(monitorId);
        int secondRun = monitorCheckScheduler.runDueChecks();
        assertThat(secondRun).isEqualTo(1);

        // Verify monitor_checks recorded DOWN
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors/{monitorId}/checks",
                        owner.workspaceId(), monitorId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("DOWN"))
                .andExpect(jsonPath("$[0].statusCode").value(500));

        // Verify OPEN incident created
        MvcResult incidentsResult = mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/incidents", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].monitorId").value(monitorId.toString()))
                .andReturn();

        String incidentId = JsonPath.read(incidentsResult.getResponse().getContentAsString(), "$[0].id");

        // Verify Webhook receiver received incident.opened JSON payload
        String openedWebhook = webhookPayloads.poll(5, TimeUnit.SECONDS);
        assertThat(openedWebhook).isNotNull();
        assertThat(JsonPath.<String>read(openedWebhook, "$.event")).isEqualTo("incident.opened");
        assertThat(JsonPath.<String>read(openedWebhook, "$.incidentId")).isEqualTo(incidentId);
        assertThat(JsonPath.<String>read(openedWebhook, "$.monitorId")).isEqualTo(monitorId.toString());
        assertThat(JsonPath.<String>read(openedWebhook, "$.status")).isEqualTo("OPEN");

        // Acknowledge the incident
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/incidents/{incidentId}/acknowledge",
                        owner.workspaceId(), incidentId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedAt", notNullValue()));

        // Toggle target back to 200 OK and trigger recovery check
        targetStatusCode.set(200);
        markMonitorDueNow(monitorId);
        int recoveryRun = monitorCheckScheduler.runDueChecks();
        assertThat(recoveryRun).isEqualTo(1);

        // Verify monitor is UP
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/monitors", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentStatus").value("UP"))
                .andExpect(jsonPath("$[0].consecutiveFailures").value(0));

        // Verify incident transitioned to RESOLVED
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/incidents", owner.workspaceId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].resolvedAt", notNullValue()));

        // Verify Webhook receiver received incident.resolved JSON payload
        String resolvedWebhook = webhookPayloads.poll(5, TimeUnit.SECONDS);
        assertThat(resolvedWebhook).isNotNull();
        assertThat(JsonPath.<String>read(resolvedWebhook, "$.event")).isEqualTo("incident.resolved");
        assertThat(JsonPath.<String>read(resolvedWebhook, "$.incidentId")).isEqualTo(incidentId);
        assertThat(JsonPath.<String>read(resolvedWebhook, "$.status")).isEqualTo("RESOLVED");
    }

    private void markMonitorDueNow(UUID monitorId) {
        jdbcTemplate.update(
                "UPDATE monitors SET next_check_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                monitorId
        );
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
                UUID.fromString(JsonPath.read(body, "$.defaultWorkspace.id"))
        );
    }

    private record AuthContext(String accessToken, UUID workspaceId) {
    }
}
