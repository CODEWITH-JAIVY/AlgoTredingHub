package com.algotradehub.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.algotradehub.backend.orders.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-demo")
@TestPropertySource(properties = {
        "app.security.jwt-secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:algotrade_local_demo_startup_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class LocalDemoStartupIntegrationTest {
    private static final String TEST_EMAIL = "local-demo@example.test";
    private static final String TEST_PASSWORD = "Temporary-Mock-Only-92";

    @Autowired private AuthService auth;
    @Autowired private RegistrationGateRepository registrationGates;
    @Autowired private InstrumentRepository instruments;

    @Test
    void localDemoSeedsTheSignupGateAndSyntheticInstrumentsAndSupportsLogin() {
        RegistrationGate gate = registrationGates.findById((short) 1).orElseThrow();
        assertFalse(gate.isInitialSignupComplete());
        assertEquals(4, instruments.count());

        AuthDtos.TokenResponse signup = auth.signup(
                new AuthDtos.SignupRequest(TEST_EMAIL, TEST_PASSWORD), "local-demo-test-signup");
        assertNotNull(signup.accessToken());
        assertTrue(registrationGates.findById((short) 1).orElseThrow().isInitialSignupComplete());

        AuthDtos.TokenResponse login = auth.login(
                new AuthDtos.LoginRequest(TEST_EMAIL, TEST_PASSWORD), "local-demo-test-login");
        assertNotNull(login.accessToken());
    }
}
