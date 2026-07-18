package vaultWeb.repositories;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vaultWeb.models.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Modifying
    @Transactional
    @Query("""
        UPDATE RefreshToken rt
        SET rt.revoked = true
        WHERE rt.user.id = :userId
    """)
    void revokeAllByUser(@Param("userId") Long userId);

    Optional<RefreshToken> findByTokenIdAndRevokedFalse(String tokenId);

    Optional<RefreshToken> findByTokenId(String tokenId);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM RefreshToken rt
        WHERE rt.expiresAt < :now
           OR (rt.revoked = true AND rt.createdAt < :cutoff)
    """)
    int deleteExpiredAndOldRevoked(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
