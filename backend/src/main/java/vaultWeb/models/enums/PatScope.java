package vaultWeb.models.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Scopes that can be granted to a personal access token (PAT).
 *
 * <p>Only scopes listed here can be requested; anything else (including {@code cloud:*}, which is
 * intentionally absent until the Cloud provider enforces the agreed contract) is rejected. The
 * endpoints each scope unlocks are defined in {@link vaultWeb.security.pat.PatEndpointRegistry}.
 */
public enum PatScope {
  PROFILE_READ("profile:read", "Read your own profile and dashboard data"),
  SECURITY_READ("security:read", "Read your own security activity log");

  private final String value;
  private final String description;

  PatScope(String value, String description) {
    this.value = value;
    this.description = description;
  }

  public String getValue() {
    return value;
  }

  public String getDescription() {
    return description;
  }

  /** Resolves a scope from its wire value (e.g. {@code profile:read}). */
  public static Optional<PatScope> fromValue(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
  }
}
