package com.algotradehub.backend.auth;

import com.algotradehub.backend.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!local-demo")
public class RedisAuthRateLimiter implements AuthRateLimiter {
    private final StringRedisTemplate redis;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final long MAX_ATTEMPTS = 10;
    public RedisAuthRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void check(String action, String identity) {
        String normalizedIdentity = "unknown";
        if (identity != null) {
            normalizedIdentity = identity.toLowerCase(Locale.ROOT);
        }
        String key = "rate:auth:" + action + ":" + digest(normalizedIdentity);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) throw protectionUnavailable();
            if (count == 1L) {
                setWindowOrFail(key);
            } else {
                Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
                if (ttlSeconds == null || ttlSeconds == -2L) throw protectionUnavailable();
                if (ttlSeconds < 0L) {
                    if (ttlSeconds != -1L) throw protectionUnavailable();
                    setWindowOrFail(key);
                }
            }
            if (count > MAX_ATTEMPTS) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMIT", "Too many authentication attempts. Try again later.");
        } catch (ApiException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw protectionUnavailable();
        }
    }

    private void setWindowOrFail(String key) {
        if (!Boolean.TRUE.equals(redis.expire(key, WINDOW))) throw protectionUnavailable();
    }

    private ApiException protectionUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROTECTION_UNAVAILABLE", "Authentication is temporarily unavailable.");
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
