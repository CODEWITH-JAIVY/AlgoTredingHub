package com.algotradehub.backend.auth;

import com.algotradehub.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LocalDemoAuthRateLimiterTest {
    @Test
    void rejectsTheEleventhAttemptForAnIdentity() {
        LocalDemoAuthRateLimiter limiter = new LocalDemoAuthRateLimiter(System::nanoTime, 4);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertDoesNotThrow(() -> limiter.check("login", "user@example.test"));
        }
        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals("AUTH_RATE_LIMIT", error.code());
    }

    @Test
    void resetsTheWindowAfterFifteenMinutes() {
        AtomicLong ticker = new AtomicLong(0L);
        LocalDemoAuthRateLimiter limiter = new LocalDemoAuthRateLimiter(ticker::get, 4);
        for (int attempt = 0; attempt < 10; attempt++) limiter.check("login", "user@example.test");

        ticker.addAndGet(Duration.ofMinutes(15).toNanos());

        assertDoesNotThrow(() -> limiter.check("login", "user@example.test"));
    }

    @Test
    void failsClosedWhenTheBoundedIdentityCacheIsFull() {
        LocalDemoAuthRateLimiter limiter = new LocalDemoAuthRateLimiter(System::nanoTime, 1);
        limiter.check("login", "first@example.test");

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "second@example.test"));

        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }
}
