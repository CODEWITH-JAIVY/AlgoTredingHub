package com.algotradehub.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_registration_gate")
public class RegistrationGate {
    @Id
    @Column(name = "gate_id")
    private Short id;

    @Column(name = "initial_signup_complete", nullable = false)
    private boolean initialSignupComplete;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegistrationGate() { }

    public boolean isInitialSignupComplete() { return initialSignupComplete; }

    public void completeInitialSignup() {
        initialSignupComplete = true;
        updatedAt = Instant.now();
    }
}
