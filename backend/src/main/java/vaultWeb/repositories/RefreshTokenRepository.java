package vaultWeb.repositories;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.transaction.Transactional;
import vaultWeb.models.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
  @Modifying
  @Transactional
  @Query(
      """
              update RefreshToken rt
              set rt.revoked = true
              where rt.user.id = :userId
            """)
  void revokeAllByUser(@Param("userId") Long userId);

  Optional<RefreshToken> findByTokenIdAndRevokedFalse(String tokenId);
  /**
   * Looks up a refresh token by its jti without filtering on revoked status. Required to
   * distinguish a replayed (revoked) token from a token that never existed.
   */
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
