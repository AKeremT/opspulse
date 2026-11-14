package com.opspulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspulse.auth.repository.RefreshTokenRepository;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.workspace.repository.WorkspaceMemberRepository;
import com.opspulse.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end integration tests for authentication, refresh token rotation,
 * and workspace endpoints backed by PostgreSQL and Redis Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthAndWorkspaceIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

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
                .apply(springSecurity())
                .build();

        refreshTokenRepository.deleteAllInBatch();
        workspaceMemberRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void registerCreatesUserAndDefaultWorkspaceAndIssuesTokens() throws Exception {
        String payload = """
                {
                  "email": "alice@opspulse.io",
                  "password": "StrongPassword123!",
                  "fullName": "Alice Johnson"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInMs").isNumber())
                .andExpect(jsonPath("$.user.id", notNullValue()))
                .andExpect(jsonPath("$.user.email").value("alice@opspulse.io"))
                .andExpect(jsonPath("$.user.fullName").value("Alice Johnson"))
                .andExpect(jsonPath("$.defaultWorkspace.id", notNullValue()))
                .andExpect(jsonPath("$.defaultWorkspace.name").value("Alice Johnson's Workspace"))
                .andExpect(jsonPath("$.defaultWorkspace.slug").value("alice-johnson-workspace"))
                .andExpect(jsonPath("$.defaultWorkspace.role").value("OWNER"))
                .andReturn();

        String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].slug").value("alice-johnson-workspace"))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
    }

    @Test
    void registerDuplicateEmailReturnsConflict() throws Exception {
        registerUser("bob@opspulse.io", "StrongPassword123!", "Bob Builder");

        String duplicatePayload = """
                {
                  "email": "BOB@opspulse.io",
                  "password": "AnotherPassword123!",
                  "fullName": "Bob Clone"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicatePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void loginSucceedsWithValidCredentialsAndRejectsWrongPassword() throws Exception {
        registerUser("carol@opspulse.io", "CorrectPass123!", "Carol Danvers");

        String validLogin = """
                {
                  "email": "carol@opspulse.io",
                  "password": "CorrectPass123!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("carol@opspulse.io"));

        String wrongPasswordLogin = """
                {
                  "email": "carol@opspulse.io",
                  "password": "WrongPassword!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPasswordLogin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"));
    }

    @Test
    void refreshRotatesTokenAndRejectsReusedRevokedToken() throws Exception {
        String registerBody = registerUser("dave@opspulse.io", "StrongPassword123!", "Dave Grohl");
        String initialRefreshToken = JsonPath.read(registerBody, "$.refreshToken");

        String refreshPayload = """
                {
                  "refreshToken": "%s"
                }
                """.formatted(initialRefreshToken);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String refreshBody = refreshResult.getResponse().getContentAsString();
        String rotatedAccessToken = JsonPath.read(refreshBody, "$.accessToken");
        String rotatedRefreshToken = JsonPath.read(refreshBody, "$.refreshToken");

        assertThat(rotatedRefreshToken).isNotEqualTo(initialRefreshToken);

        // Verify the newly issued access token is accepted on protected endpoints
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Reusing the old, already-rotated refresh token must fail with 401 and trigger reuse detection
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/refresh"));

        // Reuse detection must also revoke the user's other active refresh tokens
        String rotatedRefreshPayload = """
                {
                  "refreshToken": "%s"
                }
                """.formatted(rotatedRefreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotatedRefreshPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/refresh"));
    }

    @Test
    void logoutRevokesRefreshTokenAndReturnsNoContent() throws Exception {
        String registerBody = registerUser("eve@opspulse.io", "StrongPassword123!", "Eve Polastri");
        String refreshToken = JsonPath.read(registerBody, "$.refreshToken");

        String logoutPayload = """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutPayload))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Refresh token has been revoked"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/refresh"));
    }

    @Test
    void workspacesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Unauthorized Workspace",
                                  "slug": "unauth-ws"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workspacesCreateListAndRejectDuplicateSlug() throws Exception {
        String aliceAuth = registerUser("alice.ws@opspulse.io", "StrongPassword123!", "Alice");
        String aliceToken = JsonPath.read(aliceAuth, "$.accessToken");

        String bobAuth = registerUser("bob.ws@opspulse.io", "StrongPassword123!", "Bob");
        String bobToken = JsonPath.read(bobAuth, "$.accessToken");

        // Create workspace with explicit slug
        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Production Infra",
                                  "slug": "prod-infra"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("Production Infra"))
                .andExpect(jsonPath("$.slug").value("prod-infra"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        // Duplicate slug must be rejected with 409 Conflict
        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Another Production Infra",
                                  "slug": "prod-infra"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Workspace slug is already in use"))
                .andExpect(jsonPath("$.path").value("/api/v1/workspaces"));

        // Alice sees her default workspace + newly created workspace
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].slug", containsInAnyOrder("alice-workspace", "prod-infra")));

        // Bob only sees his own default workspace
        mockMvc.perform(get("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].slug").value("bob-workspace"));
    }

    @Test
    void validationErrorsReturnStructuredApiError() throws Exception {
        String invalidRegisterPayload = """
                {
                  "email": "",
                  "password": "short",
                  "fullName": "A"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRegisterPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed for one or more fields"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"))
                .andExpect(jsonPath("$.fieldErrors.email", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.password", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.fullName", notNullValue()));

        String authBody = registerUser("valid@opspulse.io", "StrongPassword123!", "Valid User");
        String accessToken = JsonPath.read(authBody, "$.accessToken");

        String invalidWorkspacePayload = """
                {
                  "name": "",
                  "slug": "INVALID SLUG!"
                }
                """;

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidWorkspacePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/v1/workspaces"))
                .andExpect(jsonPath("$.fieldErrors.name", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.slug", notNullValue()));
    }

    private String registerUser(String email, String password, String fullName) throws Exception {
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

        return result.getResponse().getContentAsString();
    }
}
