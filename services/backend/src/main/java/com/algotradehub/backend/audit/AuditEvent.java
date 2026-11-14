package com.algotradehub.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id private UUID id;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "event_type", nullable = false, length = 80) private String eventType;
    @Column(name = "entity_type", length = 80) private String entityType;
    @Column(name = "entity_id", length = 100) private String entityId;
    @Column(nullable = false, length = 24) private String outcome;
    @Column(name = "reason_code", length = 80) private String reasonCode;
    @Column(name = "correlation_id", nullable = false, length = 100) private String correlationId;
    @Column(name = "safe_details", columnDefinition = "text") private String safeDetails;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected AuditEvent() { }
    public AuditEvent(UUID userId, String eventType, String entityType, String entityId, String outcome,
                      String reasonCode, String correlationId, String safeDetails) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.correlationId = correlationId;
        this.safeDetails = safeDetails;
        this.createdAt = Instant.now();
    }
}
