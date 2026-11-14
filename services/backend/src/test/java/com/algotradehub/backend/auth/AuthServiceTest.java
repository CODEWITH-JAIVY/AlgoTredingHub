package com.algotradehub.backend.auth;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.risk.DailyRiskState;
import com.algotradehub.backend.risk.AccountControlRepository;
import com.algotradehub.backend.risk.DailyRiskStateRepository;
import com.algotradehub.backend.risk.RiskPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    private static final UUID USER_ID = UUID.fromString("e46497f4-3d73-41d7-8d0d-dfca593e8bd9");
    private static final UUID SESSION_ID = UUID.fromString("3ca3e287-03b8-4ec7-a78d-f1bc40666af2");
    private static final UUID FAMILY_ID = UUID.fromString("159992da-2757-40dc-9f82-3e1a64f23985");
    private static final String REFRESH_TOKEN = "one-time-refresh-token";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-21T04:30:00Z"), ZoneOffset.UTC);

    @Mock private UserAccountRepository users;
    @Mock private RegistrationGateRepository registrationGates;
    @Mock private RefreshSessionRepository refreshSessions;
    @Mock private RiskPolicyRepository riskPolicies;
    @Mock private DailyRiskStateRepository dailyRiskStates;
    @Mock private AccountControlRepository controls;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtEncoder jwtEncoder;
    @Mock private AuditService audit;
    private AuthService service;

    @BeforeEach void setUp() {
        service = new AuthService(users, registrationGates, refreshSessions, riskPolicies, dailyRiskStates, controls,
                passwordEncoder, authenticationManager, jwtEncoder, audit, FIXED_CLOCK, 10, 14, true);
    }

    @Test void signupNormalizesEmailEncodesPasswordAndInitializesRiskState() {
        RegistrationGate gate = mock(RegistrationGate.class);
        when(registrationGates.findByIdForUpdate((short) 1)).thenReturn(Optional.of(gate));
        when(users.count()).thenReturn(0L);
        when(users.existsByEmailIgnoreCase("trader@example.test")).thenReturn(false);
        when(passwordEncoder.encode("StrongPassword123!")).thenReturn("bcrypt-hash");
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubJwtEncoding();

        AuthDtos.TokenResponse result = service.signup(
                new AuthDtos.SignupRequest(" Trader@Example.Test ", "StrongPassword123!"), "corr-signup");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(userCaptor.capture());
        assertEquals("trader@example.test", userCaptor.getValue().getEmail());
        assertEquals("bcrypt-hash", userCaptor.getValue().getPasswordHash());
        assertFalse(userCaptor.getValue().getPasswordHash().equals("StrongPassword123!"));
        verify(gate).completeInitialSignup();
        verify(registrationGates).save(gate);
        verify(riskPolicies).save(any());
        verify(controls).save(any());
        ArgumentCaptor<DailyRiskState> dailyStateCaptor = ArgumentCaptor.forClass(DailyRiskState.class);
        verify(dailyRiskStates).save(dailyStateCaptor.capture());
        assertEquals(LocalDate.of(2026, 10, 21), dailyStateCaptor.getValue().getSessionDate());
        verify(audit).record(eq(result.userId()), eq("AUTH_SIGNUP"), eq("USER"), eq(result.userId().toString()),
                eq("SUCCESS"), isNull(), eq("corr-signup"), isNull());
    }

    @Test void signupRejectsPasswordsOverTheUtf8ByteLimitBeforeDatabaseAccess() {
        ApiException error = assertThrows(ApiException.class, () -> service.signup(
                new AuthDtos.SignupRequest("trader@example.test", "💰".repeat(19)), "corr-long-password"));
        assertEquals("PASSWORD_TOO_LONG", error.code());
        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        verifyNoInteractions(registrationGates, users, passwordEncoder, refreshSessions);
    }

    @Test void failedLoginUsesGenericErrorAndWritesRejectionAudit() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("wrong password"));
        ApiException error = assertThrows(ApiException.class, () -> service.login(
                new AuthDtos.LoginRequest(" Trader@Example.Test ", "wrong-password"), "corr-login"));
        assertEquals("INVALID_CREDENTIALS", error.code());
        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        verify(audit).record(isNull(), eq("AUTH_LOGIN"), eq("USER"), isNull(), eq("REJECTED"),
                eq("INVALID_CREDENTIALS"), eq("corr-login"), isNull());
        verify(users, never()).findByEmailIgnoreCase(anyString());
    }

    @Test void refreshRotatesToANewOpaqueTokenInTheSameFamily() {
        UserAccount user = new UserAccount(USER_ID, "trader@example.test", "bcrypt-hash");
        RefreshSession current = session(Instant.now().plusSeconds(60));
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.of(current));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshSessions.revokeIfActive(eq(SESSION_ID), any(Instant.class), any(UUID.class))).thenReturn(1);
        stubJwtEncoding();

        AuthDtos.TokenResponse result = service.refresh(new AuthDtos.RefreshRequest(REFRESH_TOKEN), "corr-refresh");

        assertEquals("signed-access-token", result.accessToken());
        assertEquals(USER_ID, result.userId());
        assertNotNull(result.refreshToken());
        assertFalse(result.refreshToken().equals(REFRESH_TOKEN));
        assertEquals(64, result.refreshToken().length());
        ArgumentCaptor<RefreshSession> sessionCaptor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(refreshSessions).save(sessionCaptor.capture());
        assertEquals(USER_ID, sessionCaptor.getValue().getUserId());
        assertEquals(FAMILY_ID, sessionCaptor.getValue().getFamilyId());
    }

    @Test void refreshReplayRevokesTheWholeTokenFamilyAndKeepsThatRevocationCommitted() throws Exception {
        UserAccount user = new UserAccount(USER_ID, "trader@example.test", "bcrypt-hash");
        RefreshSession current = session(Instant.now().plusSeconds(60));
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.of(current));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshSessions.revokeIfActive(eq(SESSION_ID), any(Instant.class), any(UUID.class))).thenReturn(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.refresh(new AuthDtos.RefreshRequest(REFRESH_TOKEN), "corr-replay"));

        assertEquals("INVALID_REFRESH_TOKEN", error.code());
        verify(refreshSessions).revokeActiveFamily(eq(FAMILY_ID), any(Instant.class));
        verify(audit).record(eq(USER_ID), eq("AUTH_REFRESH"), eq("SESSION"), eq(SESSION_ID.toString()),
                eq("REJECTED"), eq("REFRESH_REPLAY"), eq("corr-replay"), isNull());
        Transactional transactional = AuthService.class.getMethod("refresh", AuthDtos.RefreshRequest.class, String.class)
                .getAnnotation(Transactional.class);
        assertTrue(Arrays.asList(transactional.noRollbackFor()).contains(ApiException.class));
    }

    @Test void refreshRejectsExpiredTokenAndAuditsWithoutIssuingAnotherToken() {
        RefreshSession expired = session(Instant.now().minusSeconds(1));
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.of(expired));

        ApiException error = assertThrows(ApiException.class,
                () -> service.refresh(new AuthDtos.RefreshRequest(REFRESH_TOKEN), "corr-expired"));

        assertEquals("INVALID_REFRESH_TOKEN", error.code());
        verify(audit).record(eq(USER_ID), eq("AUTH_REFRESH"), eq("SESSION"), eq(SESSION_ID.toString()),
                eq("REJECTED"), eq("EXPIRED_REFRESH_TOKEN"), eq("corr-expired"), isNull());
        verify(users, never()).findById(any());
        verify(refreshSessions, never()).save(any());
    }

    @Test void refreshForUnavailableAccountRevokesItsSessions() {
        UserAccount suspended = mock(UserAccount.class);
        when(suspended.getStatus()).thenReturn("SUSPENDED");
        RefreshSession current = session(Instant.now().plusSeconds(60));
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.of(current));
        when(users.findById(USER_ID)).thenReturn(Optional.of(suspended));

        ApiException error = assertThrows(ApiException.class,
                () -> service.refresh(new AuthDtos.RefreshRequest(REFRESH_TOKEN), "corr-suspended"));

        assertEquals("ACCOUNT_UNAVAILABLE", error.code());
        verify(refreshSessions).revokeAllForUser(eq(USER_ID), any(Instant.class));
        verify(audit).record(eq(USER_ID), eq("AUTH_REFRESH"), eq("SESSION"), eq(SESSION_ID.toString()),
                eq("REJECTED"), eq("ACCOUNT_UNAVAILABLE"), eq("corr-suspended"), isNull());
    }

    @Test void logoutRevokesTheWholeTokenFamilyEvenWhenGivenAnOlderRotatedToken() {
        RefreshSession rotatedToken = session(Instant.now().plusSeconds(60));
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.of(rotatedToken));

        service.logout(USER_ID, new AuthDtos.LogoutRequest(REFRESH_TOKEN), "corr-logout");

        verify(refreshSessions).revokeActiveFamily(eq(FAMILY_ID), eq(FIXED_CLOCK.instant()));
        verify(refreshSessions, never()).revokeIfActive(any(), any(), any());
        verify(audit).record(eq(USER_ID), eq("AUTH_LOGOUT"), eq("USER"), eq(USER_ID.toString()),
                eq("SUCCESS"), isNull(), eq("corr-logout"), isNull());
    }

    @Test void invalidRefreshTokenIsAuditedWithoutRevealingItsValue() {
        when(refreshSessions.findByTokenHash(hash(REFRESH_TOKEN))).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> service.refresh(new AuthDtos.RefreshRequest(REFRESH_TOKEN), "corr-invalid"));

        assertEquals("INVALID_REFRESH_TOKEN", error.code());
        verify(audit).record(isNull(), eq("AUTH_REFRESH"), eq("SESSION"), isNull(), eq("REJECTED"),
                eq("INVALID_REFRESH_TOKEN"), eq("corr-invalid"), isNull());
        verify(refreshSessions, never()).revokeActiveFamily(any(), any());
    }

    private RefreshSession session(Instant expiresAt) {
        return new RefreshSession(SESSION_ID, USER_ID, hash(REFRESH_TOKEN), FAMILY_ID, expiresAt);
    }

    private void stubJwtEncoding() {
        Jwt jwt = Jwt.withTokenValue("signed-access-token").header("alg", "HS256").subject("trader@example.test").build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
