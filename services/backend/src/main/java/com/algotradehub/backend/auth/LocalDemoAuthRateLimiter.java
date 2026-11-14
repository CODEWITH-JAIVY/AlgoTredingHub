package com.algotradehub.backend.auth;

import com.algotradehub.backend.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

@Component
@Profile("local-demo")
public class LocalDemoAuthRateLimiter implements AuthRateLimiter {
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_IDENTITIES = 4_096;
    private static final long WINDOW_NANOS = Duration.ofMinutes(15).toNanos();
    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier ticker;
    private final int maxIdentities;

    public LocalDemoAuthRateLimiter() {
        this(System::nanoTime, MAX_IDENTITIES);
    }

    LocalDemoAuthRateLimiter(LongSupplier ticker, int maxIdentities) {
        if (maxIdentities < 1) throw new IllegalArgumentException("At least one authentication identity must be allowed.");
        this.ticker = ticker;
        this.maxIdentities = maxIdentities;
    }

    @Override
    public synchronized void check(String action, String identity) {
        String normalizedIdentity = "unknown";
        if (identity != null) {
            normalizedIdentity = identity.toLowerCase(Locale.ROOT);
        }
        String key = digest(action + ":" + normalizedIdentity);
        long now = ticker.getAsLong();
        Window current = windows.get(key);
        if (current == null || isExpired(now, current.expiresAtNanos())) {
            removeExpired(now);
            if (windows.size() >= maxIdentities) throw protectionUnavailable();
            current = new Window(now + WINDOW_NANOS, 0);
        }
        int attempts = current.attempts() + 1;
        windows.put(key, new Window(current.expiresAtNanos(), attempts));
        if (attempts > MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMIT", "Too many authentication attempts. Try again later.");
        }
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(now, iterator.next().getValue().expiresAtNanos())) iterator.remove();
        }
    }

    private boolean isExpired(long now, long expiresAt) {
        return now - expiresAt >= 0;
    }

    private ApiException protectionUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROTECTION_UNAVAILABLE", "Authentication is temporarily unavailable.");
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private record Window(long expiresAtNanos, int attempts) { }
}
