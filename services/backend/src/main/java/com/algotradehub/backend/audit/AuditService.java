package com.algotradehub.backend.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository events;
    public AuditService(AuditEventRepository events) { this.events = events; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String type, String entityType, String entityId, String outcome,
                       String reasonCode, String correlationId, String safeDetails) {
        String safeCorrelation = "unavailable";
        if (correlationId != null) {
            safeCorrelation = correlationId.substring(0, Math.min(100, correlationId.length()));
        }
        String safeData = null;
        if (safeDetails != null) {
            safeData = safeDetails.substring(0, Math.min(1000, safeDetails.length()));
        }
        events.save(new AuditEvent(userId, type, entityType, entityId, outcome, reasonCode, safeCorrelation, safeData));
    }
}
