package com.algotradehub.backend.auth;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.risk.AccountControlRepository;
import com.algotradehub.backend.risk.AccountControl;
import com.algotradehub.backend.risk.RiskPolicy;
import com.algotradehub.backend.risk.RiskPolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final RegistrationGateRepository registrationGates;
    private final RefreshSessionRepository refreshSessions;
    private final RiskPolicyRepository riskPolicies;
    private final com.algotradehub.backend.risk.DailyRiskStateRepository dailyRiskStates;
    private final AccountControlRepository controls;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final AuditService audit;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final boolean singleUserMode;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserAccountRepository users, RegistrationGateRepository registrationGates, RefreshSessionRepository refreshSessions,
                       RiskPolicyRepository riskPolicies, com.algotradehub.backend.risk.DailyRiskStateRepository dailyRiskStates, AccountControlRepository controls,
                       PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
                       JwtEncoder jwtEncoder, AuditService audit, Clock clock,
                       @Value("${app.security.access-token-minutes:10}") long accessMinutes,
                       @Value("${app.security.refresh-token-days:14}") long refreshDays,
                       @Value("${app.registration.single-user-mode:true}") boolean singleUserMode) {
        this.users = users;
        this.registrationGates = registrationGates;
        this.refreshSessions = refreshSessions;
        this.riskPolicies = riskPolicies;
        this.dailyRiskStates = dailyRiskStates;
        this.controls = controls;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.audit = audit;
        this.clock = clock;
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
        this.singleUserMode = singleUserMode;
    }

    @Transactional
    public AuthDtos.TokenResponse signup(AuthDtos.SignupRequest request, String correlationId) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must be at most 72 UTF-8 bytes.");
        String email = normalize(request.email());
        RegistrationGate gate = registrationGates.findByIdForUpdate((short) 1)
                .orElseThrow(() -> new IllegalStateException("Registration gate row is missing."));
        if (singleUserMode && (gate.isInitialSignupComplete() || users.count() > 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNUP_CLOSED", "Initial account setup is complete. Sign in with the existing account.");
        }
        if (users.existsByEmailIgnoreCase(email)) throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "An account with this email already exists.");
        UserAccount user = users.save(new UserAccount(UUID.randomUUID(), email, passwordEncoder.encode(request.password())));
        riskPolicies.save(new RiskPolicy(user.getId()));
        controls.save(new AccountControl(user.getId()));
        dailyRiskStates.save(new com.algotradehub.backend.risk.DailyRiskState(user.getId(),
                java.time.LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")))));
        if (singleUserMode) {
            gate.completeInitialSignup();
            registrationGates.save(gate);
        }
        audit.record(user.getId(), "AUTH_SIGNUP", "USER", user.getId().toString(), "SUCCESS", null, correlationId, null);
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request, String correlationId) {
        String email = normalize(request.email());
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException ex) {
            audit.record(null, "AUTH_LOGIN", "USER", null, "REJECTED", "INVALID_CREDENTIALS", correlationId, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        UserAccount user = users.findByEmailIgnoreCase(email).orElseThrow();
        audit.record(user.getId(), "AUTH_LOGIN", "USER", user.getId().toString(), "SUCCESS", null, correlationId, null);
        return issue(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request, String correlationId) {
        Instant now = Instant.now();
        String oldHash = hash(request.refreshToken());
        RefreshSession current = refreshSessions.findByTokenHash(oldHash).orElse(null);
        if (current == null) {
            audit.record(null, "AUTH_REFRESH", "SESSION", null, "REJECTED", "INVALID_REFRESH_TOKEN", correlationId, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
        }
        if (current.getExpiresAt().isBefore(now)) {
            audit.record(current.getUserId(), "AUTH_REFRESH", "SESSION", current.getId().toString(), "REJECTED", "EXPIRED_REFRESH_TOKEN", correlationId, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
        }
        UserAccount user = users.findById(current.getUserId()).filter(u -> u.getStatus().equals("ACTIVE")).orElse(null);
        if (user == null) {
            refreshSessions.revokeAllForUser(current.getUserId(), now);
            audit.record(current.getUserId(), "AUTH_REFRESH", "SESSION", current.getId().toString(), "REJECTED", "ACCOUNT_UNAVAILABLE", correlationId, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "This account is unavailable.");
        }
        UUID nextId = UUID.randomUUID();
        if (refreshSessions.revokeIfActive(current.getId(), now, nextId) != 1) {
            refreshSessions.revokeActiveFamily(current.getFamilyId(), now);
            audit.record(user.getId(), "AUTH_REFRESH", "SESSION", current.getId().toString(), "REJECTED", "REFRESH_REPLAY", correlationId, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token has already been used.");
        }
        return issue(user, current.getFamilyId(), nextId);
    }

    @Transactional
    public void logout(UUID userId, AuthDtos.LogoutRequest request, String correlationId) {
        Instant now = clock.instant();
        refreshSessions.findByTokenHash(hash(request.refreshToken())).filter(s -> s.getUserId().equals(userId))
                .ifPresent(s -> refreshSessions.revokeActiveFamily(s.getFamilyId(), now));
        audit.record(userId, "AUTH_LOGOUT", "USER", userId.toString(), "SUCCESS", null, correlationId, null);
    }

    private AuthDtos.TokenResponse issue(UserAccount user, UUID familyId) { return issue(user, familyId, UUID.randomUUID()); }
    private AuthDtos.TokenResponse issue(UserAccount user, UUID familyId, UUID sessionId) {
        Instant now = Instant.now();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("algotrade-hub").subject(user.getEmail()).issuedAt(now)
                        .expiresAt(now.plus(accessTtl)).claim("uid", user.getId().toString()).build())).getTokenValue();
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshSessions.save(new RefreshSession(sessionId, user.getId(), hash(refresh), familyId, now.plus(refreshTtl)));
        return new AuthDtos.TokenResponse(access, refresh, "Bearer", accessTtl.toSeconds(), user.getId());
    }

    private String normalize(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
    }
}
