package vaultWeb.repositories;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vaultWeb.models.PersonalAccessToken;
import vaultWeb.models.User;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, Long> {

  // Fetch-graph semantics: anything not listed becomes lazy, so list scopes explicitly.
  @EntityGraph(attributePaths = {"user", "scopes"})
  Optional<PersonalAccessToken> findByPublicId(String publicId);

  boolean existsByPublicId(String publicId);

  List<PersonalAccessToken> findAllByUserOrderByCreatedAtDesc(User user);

  Optional<PersonalAccessToken> findByPublicIdAndUser(String publicId, User user);

  @Query(
      """
      select count(t) from PersonalAccessToken t
      where t.user = :user and t.revokedAt is null and t.expiresAt > :now
      """)
  long countActiveByUser(@Param("user") User user, @Param("now") Instant now);

  @Modifying
  @Transactional
  @Query(
      """
      update PersonalAccessToken t set t.revokedAt = :now
      where t.user = :user and t.revokedAt is null
      """)
  int revokeAllActiveByUser(@Param("user") User user, @Param("now") Instant now);

  /**
   * Updates {@code lastUsedAt} only if it is unset or older than {@code threshold}, bounding writes
   * to at most one per token per granularity window.
   */
  @Modifying
  @Transactional
  @Query(
      """
      update PersonalAccessToken t set t.lastUsedAt = :now
      where t.id = :id and (t.lastUsedAt is null or t.lastUsedAt < :threshold)
      """)
  int touchLastUsed(
      @Param("id") Long id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
