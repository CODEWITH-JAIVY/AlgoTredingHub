package com.algotradehub.backend.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_controls")
public class AccountControl {
    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "kill_switch_active", nullable = false) private boolean killSwitchActive;
    @Column(name = "kill_switch_reason", length = 500) private String killSwitchReason;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected AccountControl() { }
    public AccountControl(UUID userId) {
        this.userId = userId;
        this.killSwitchActive = false;
        this.updatedAt = Instant.now();
    }
    public UUID getUserId() { return userId; }
    public boolean isKillSwitchActive() { return killSwitchActive; }
    public String getKillSwitchReason() { return killSwitchReason; }
    public void activate(String reason) {
        this.killSwitchActive = true;
        this.killSwitchReason = reason;
        this.updatedAt = Instant.now();
    }
    public void deactivate() {
        this.killSwitchActive = false;
        this.killSwitchReason = null;
        this.updatedAt = Instant.now();
    }
}
