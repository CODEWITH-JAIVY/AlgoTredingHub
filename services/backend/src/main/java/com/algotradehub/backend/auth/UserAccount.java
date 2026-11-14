package com.algotradehub.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 320) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UserAccount() { }
    public UserAccount(UUID id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getStatus() { return status; }
}
