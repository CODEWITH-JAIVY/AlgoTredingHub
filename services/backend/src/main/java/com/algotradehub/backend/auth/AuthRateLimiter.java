package com.algotradehub.backend.auth;

public interface AuthRateLimiter {
    void check(String action, String identity);
}
