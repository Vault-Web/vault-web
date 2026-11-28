package vaultWeb.dtos.pat;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import vaultWeb.models.PersonalAccessToken;
import vaultWeb.models.enums.PatScope;

/** Non-secret PAT metadata. Never contains the token or its digest. */
public record PersonalAccessTokenDto(
    String publicId,
    String name,
    List<String> scopes,
    String audience,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt,
    String status) {

  public static PersonalAccessTokenDto from(PersonalAccessToken token, Instant now) {
    String status;
    if (token.getRevokedAt() != null) {
      status = "REVOKED";
    } else if (!token.getExpiresAt().isAfter(now)) {
      status = "EXPIRED";
    } else {
      status = "ACTIVE";
    }
    return new PersonalAccessTokenDto(
        token.getPublicId(),
        token.getName(),
        token.getScopes().stream()
            .sorted(Comparator.comparing(Enum::ordinal))
            .map(PatScope::getValue)
            .toList(),
        token.getAudience(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.getLastUsedAt(),
        token.getRevokedAt(),
        status);
  }
}
