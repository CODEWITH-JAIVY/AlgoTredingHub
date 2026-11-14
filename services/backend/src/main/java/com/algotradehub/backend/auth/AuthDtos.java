package com.algotradehub.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() { }
    public record SignupRequest(@NotBlank @Email @Size(max = 320) String email,
                                @NotBlank @Size(min = 12, max = 72) String password) { }
    public record LoginRequest(@NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Size(max = 72) String password) { }
    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) { }
    public record LogoutRequest(@NotBlank @Size(max = 200) String refreshToken) { }
    public record TokenResponse(String accessToken, String refreshToken, String tokenType,
                                long accessExpiresInSeconds, UUID userId) { }
}