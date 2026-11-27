package vaultWeb.models;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import vaultWeb.models.enums.PatScope;

/**
 * A personal access token (PAT) for CLI and automation clients.
 *
 * <p>Only a SHA-256 digest of the full token is persisted. {@code publicId} is a non-secret lookup
 * identifier embedded in the token; it is never sufficient on its own to authenticate.
 */
@Entity
@Getter
@Setter
@Table(
    name = "personal_access_token",
    uniqueConstraints = @UniqueConstraint(name = "uk_pat_public_id", columnNames = "public_id"),
    indexes = @Index(name = "idx_pat_user", columnList = "user_id"))
public class PersonalAccessToken {

  /** The only audience supported today: the core Vault Web backend. */
  public static final String AUDIENCE_CORE = "core";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false, length = 16)
  private String publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "personal_access_token_scope",
      joinColumns = @JoinColumn(name = "token_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 32)
  private Set<PatScope> scopes = new HashSet<>();

  @Column(nullable = false, length = 32)
  private String audience = AUDIENCE_CORE;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant lastUsedAt;

  private Instant revokedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
