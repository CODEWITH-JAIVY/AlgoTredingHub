package com.algotradehub.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile("local-demo")
public class LocalDemoSafetyGuard implements ApplicationRunner, EnvironmentAware {
    private Environment environment;
    @Value("${server.address:}") private String bindAddress;
    @Value("${app.trading.mode:MOCK}") private String tradingMode;
    @Value("${app.trading.live-orders-enabled:false}") private boolean liveOrdersEnabled;
    @Value("${app.registration.single-user-mode:true}") private boolean singleUserMode;

    @Override
    public void setEnvironment(Environment configuredEnvironment) {
        this.environment = configuredEnvironment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (environment.acceptsProfiles(Profiles.of("live"))) {
            throw new IllegalStateException("The local-demo profile cannot be combined with the live profile.");
        }
        if (!"127.0.0.1".equals(bindAddress)) {
            throw new IllegalStateException("The local-demo backend must bind only to 127.0.0.1.");
        }
        if (!"MOCK".equalsIgnoreCase(tradingMode) || liveOrdersEnabled) {
            throw new IllegalStateException("The local-demo backend requires MOCK mode with live orders disabled.");
        }
        if (!singleUserMode) {
            throw new IllegalStateException("The local-demo backend requires single-user registration mode.");
        }
    }
}
