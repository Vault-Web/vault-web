package vaultWeb.repositories;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.models.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenId(String tokenId);

  Optional<RefreshToken> findByTokenIdAndRevokedFalse(String tokenId);

  @Transactional
  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.revoked = true, r.revokeReason = :reason "
          + "WHERE r.user.id = :userId AND r.revoked = false")
  void revokeAllByUser(
      @Param("userId") Long userId, @Param("reason") RefreshToken.RevokeReason reason);

  @Modifying
  @Query(
      "DELETE FROM RefreshToken r WHERE r.expiresAt < :now "
          + "OR (r.revoked = true AND r.expiresAt < :cutoff)")
  int deleteExpiredAndOldRevoked(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
