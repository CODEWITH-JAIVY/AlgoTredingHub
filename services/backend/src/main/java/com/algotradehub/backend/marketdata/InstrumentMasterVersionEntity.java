package com.algotradehub.backend.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "instrument_master_versions")
public class InstrumentMasterVersionEntity {
    @Id @Column(length = 64) private String version;
    @Column(name = "source_id", nullable = false, length = 50) private String sourceId;
    @Column(name = "imported_at", nullable = false) private Instant importedAt;
    @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
    @Column(nullable = false, length = 64) private String checksum;
    @Column(nullable = false) private boolean synthetic;
    @Column(nullable = false, length = 16) private String status;

    protected InstrumentMasterVersionEntity() { }
    public InstrumentMasterVersionEntity(String version, String sourceId, Instant importedAt,
                                         Instant effectiveFrom, String checksum, boolean synthetic, String status) {
        this.version = version;
        this.sourceId = sourceId;
        this.importedAt = importedAt;
        this.effectiveFrom = effectiveFrom;
        this.checksum = checksum;
        this.synthetic = synthetic;
        this.status = status;
    }
}
