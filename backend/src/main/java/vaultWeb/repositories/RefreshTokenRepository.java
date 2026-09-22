package vaultWeb.repositories;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
  @Query(
      """
              update RefreshToken rt
              set rt.revoked = true
              where rt.user.id = :userId AND r.revoked = false
            """)
  void revokeAllByUser(@Param("userId") Long userId);

  Optional<RefreshToken> findByTokenIdAndRevokedFalse(String tokenId);
  Optional<RefreshToken> findByTokenId(String tokenId);

  @Modifying
  @Transactional
  @Query(
      """
                DELETE FROM RefreshToken rt
                WHERE rt.expiresAt < :now
                   OR (rt.revoked = true AND rt.createdAt < :cutoff)
            """)

  int deleteExpiredAndOldRevoked(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
