package com.algotradehub.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter rateLimiter;
    public AuthController(AuthService auth, AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/signup")
    public AuthDtos.TokenResponse signup(@Valid @RequestBody AuthDtos.SignupRequest body, HttpServletRequest request) {
        rateLimiter.check("signup", request.getRemoteAddr());
        return auth.signup(body, correlation(request));
    }
    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest body, HttpServletRequest request) {
        rateLimiter.check("login", body.email() + "|" + request.getRemoteAddr());
        return auth.login(body, correlation(request));
    }
    @PostMapping("/refresh")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest body, HttpServletRequest request) {
        rateLimiter.check("refresh", request.getRemoteAddr());
        return auth.refresh(body, correlation(request));
    }
    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthDtos.LogoutRequest body, HttpServletRequest request) {
        auth.logout(UUID.fromString(jwt.getClaimAsString("uid")), body, correlation(request));
    }
    private String correlation(HttpServletRequest request) { return String.valueOf(request.getAttribute("correlationId")); }
}
