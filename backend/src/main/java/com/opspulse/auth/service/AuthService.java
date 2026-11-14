package com.opspulse.auth.service;

import com.opspulse.auth.domain.RefreshToken;
import com.opspulse.auth.domain.User;
import com.opspulse.auth.dto.AuthResponse;
import com.opspulse.auth.dto.LoginRequest;
import com.opspulse.auth.dto.RefreshTokenRequest;
import com.opspulse.auth.dto.RegisterRequest;
import com.opspulse.auth.dto.UserSummaryResponse;
import com.opspulse.auth.repository.RefreshTokenRepository;
import com.opspulse.auth.repository.UserRepository;
import com.opspulse.common.exception.ConflictException;
import com.opspulse.common.exception.UnauthorizedException;
import com.opspulse.workspace.dto.WorkspaceResponse;
import com.opspulse.workspace.service.WorkspaceService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles user registration, credential login, and refresh token rotation.
 */
@Service
public class AuthService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WorkspaceService workspaceService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            WorkspaceService workspaceService,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.workspaceService = workspaceService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        User user = userRepository.save(new User(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim()
        ));

        WorkspaceResponse defaultWorkspace = workspaceService.createDefaultWorkspaceForUser(user);
        return issueTokenPair(user, defaultWorkspace);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password())
        );

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (!user.isActive()) {
            throw new UnauthorizedException("User account is disabled");
        }

        return issueTokenPair(user, null);
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = jwtService.hashRefreshToken(request.refreshToken());
        RefreshToken existingToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (existingToken.isRevoked()) {
            // Reuse of an already-revoked token indicates potential theft; revoke all active tokens for this user.
            refreshTokenRepository.revokeAllActiveByUserId(existingToken.getUser().getId());
            throw new UnauthorizedException("Refresh token has been revoked");
        }

        if (existingToken.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Refresh token is expired");
        }

        User user = existingToken.getUser();
        if (!user.isActive()) {
            throw new UnauthorizedException("User account is disabled");
        }

        existingToken.revoke();
        refreshTokenRepository.save(existingToken);

        return issueTokenPair(user, null);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        String tokenHash = jwtService.hashRefreshToken(request.refreshToken());
        refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .ifPresent(token -> {
                    if (!token.isRevoked()) {
                        token.revoke();
                        refreshTokenRepository.save(token);
                    }
                });
    }

    private AuthResponse issueTokenPair(User user, WorkspaceResponse defaultWorkspace) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateOpaqueRefreshToken();
        String refreshTokenHash = jwtService.hashRefreshToken(rawRefreshToken);

        refreshTokenRepository.save(new RefreshToken(
                UUID.randomUUID(),
                user,
                refreshTokenHash,
                jwtService.computeRefreshTokenExpiry()
        ));

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                TOKEN_TYPE_BEARER,
                jwtService.getAccessExpirationMs(),
                new UserSummaryResponse(user.getId(), user.getEmail(), user.getFullName()),
                defaultWorkspace
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
