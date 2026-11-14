package com.algotradehub.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    Optional<RefreshSession> findByTokenHash(String tokenHash);
    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now, s.replacedBy = :replacement where s.id = :id and s.revokedAt is null and s.expiresAt > :now")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now, @Param("replacement") UUID replacement);
    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshSession s set s.revokedAt = :now where s.familyId = :familyId and s.revokedAt is null")
    int revokeActiveFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
