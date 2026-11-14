package com.algotradehub.backend.auth;

import com.algotradehub.backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    private RedisAuthRateLimiter limiter;

    @BeforeEach void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        limiter = new RedisAuthRateLimiter(redis);
    }

    @Test void nullCounterResponseFailsClosed() {
        when(values.increment(anyString())).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test void inabilityToSetTheRateWindowFailsClosed() {
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), eq(Duration.ofMinutes(15)))).thenReturn(false);
        ApiException error = assertThrows(ApiException.class, () -> limiter.check("signup", "127.0.0.1"));
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void redisConnectionFailureFailsClosed() {
        when(values.increment(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        ApiException error = assertThrows(ApiException.class, () -> limiter.check("refresh", "127.0.0.1"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void repairsCounterExpiryAfterInterruptedFirstRequest() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);
        when(redis.expire(anyString(), eq(Duration.ofMinutes(15)))).thenReturn(true);

        limiter.check("login", "user@example.test");

        verify(redis).expire(anyString(), eq(Duration.ofMinutes(15)));
    }

    @Test void missingCounterDuringTtlCheckFailsClosed() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void nullTtlResponseFailsClosed() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void inabilityToRepairCounterExpiryFailsClosed() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);
        when(redis.expire(anyString(), eq(Duration.ofMinutes(15)))).thenReturn(false);

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void nullCounterExpiryConfirmationFailsClosed() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);
        when(redis.expire(anyString(), eq(Duration.ofMinutes(15)))).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void ttlLookupFailureFailsClosed() {
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("AUTH_PROTECTION_UNAVAILABLE", error.code());
    }

    @Test void eleventhAuthenticationAttemptIsRejected() {
        when(values.increment(anyString())).thenReturn(11L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(60L);
        ApiException error = assertThrows(ApiException.class, () -> limiter.check("login", "user@example.test|127.0.0.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("AUTH_RATE_LIMIT", error.code());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test void identityIsHashedBeforeItBecomesARedisKey() {
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), eq(Duration.ofMinutes(15)))).thenReturn(true);
        limiter.check("login", "Trader@Example.Test|127.0.0.1");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(values).increment(keyCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("rate:auth:login:"));
        assertFalse(keyCaptor.getValue().contains("Trader@Example.Test"));
        assertFalse(keyCaptor.getValue().contains("127.0.0.1"));
    }
}
