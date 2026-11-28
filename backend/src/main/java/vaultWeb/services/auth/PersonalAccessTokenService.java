package vaultWeb.services.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.dtos.pat.CreatePersonalAccessTokenRequest;
import vaultWeb.dtos.pat.CreatedPersonalAccessTokenDto;
import vaultWeb.dtos.pat.PatScopeDto;
import vaultWeb.dtos.pat.PersonalAccessTokenDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.PersonalAccessTokenNotFoundException;
import vaultWeb.models.PersonalAccessToken;
import vaultWeb.models.User;
import vaultWeb.models.enums.PatScope;
import vaultWeb.repositories.PersonalAccessTokenRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.TokenHashUtil;
import vaultWeb.security.pat.PatAuthenticationToken;
import vaultWeb.security.pat.PatEndpointRegistry;
import vaultWeb.security.pat.PatTokens;

/**
 * Lifecycle and verification of personal access tokens (PATs).
 *
 * <p>Security properties:
 *
 * <ul>
 *   <li>The raw token is returned once from {@link #create}; only its SHA-256 digest is stored.
 *   <li>Verification looks up by the non-secret public id, then compares digests in constant time.
 *   <li>Every request is checked against the database (no cache), so revocation and expiry take
 *       effect on the very next request.
 *   <li>Lifecycle operations refuse PAT-authenticated callers, so a PAT can never mint a PAT.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {

  public static final Duration MAX_LIFETIME = Duration.ofDays(365);
  public static final int MAX_ACTIVE_TOKENS_PER_USER = 20;
  public static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(5);

  /** Digest compared against when the public id is unknown, to keep timing uniform. */
  private static final String DUMMY_HASH = TokenHashUtil.sha256("vw_pat_dummy");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int PUBLIC_ID_ATTEMPTS = 5;

  private final PersonalAccessTokenRepository tokenRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final PatEndpointRegistry endpointRegistry;

  public List<PatScopeDto> supportedScopes() {
    return EnumSet.allOf(PatScope.class).stream()
        .map(
            scope ->
                new PatScopeDto(
                    scope.getValue(),
                    scope.getDescription(),
                    endpointRegistry.rules().stream()
                        .filter(r -> r.scope() == scope)
                        .map(r -> r.method().name() + " " + r.pattern())
                        .toList()))
        .toList();
  }

  /**
   * Creates a new token after re-verifying the owner's password.
   *
   * @throws UnauthorizedException if the current password is wrong
   * @throws IllegalArgumentException for unsupported scopes, invalid lifetime or name, or when the
   *     active-token limit is reached
   * @throws AccessDeniedException if the caller is authenticated with a PAT
   */
  @Transactional
  public CreatedPersonalAccessTokenDto create(
      String username, CreatePersonalAccessTokenRequest request) {
    requireInteractiveSession();
    // The password change takes the same account lock before it revokes the user's tokens.
    User owner =
        userRepository
            .findByUsernameForUpdate(username)
            .orElseThrow(() -> new UnauthorizedException("No authenticated user found"));
    if (request.getCurrentPassword() == null
        || !passwordEncoder.matches(request.getCurrentPassword(), owner.getPassword())) {
      throw new UnauthorizedException("Current password is incorrect");
    }

    String name = request.getName() == null ? "" : request.getName().strip();
    if (name.isEmpty() || name.length() > 64) {
      throw new IllegalArgumentException("Token name must be 1-64 characters");
    }
    Set<PatScope> scopes = parseScopes(request.getScopes());
    Integer days = request.getExpiresInDays();
    if (days == null || days < 1 || Duration.ofDays(days).compareTo(MAX_LIFETIME) > 0) {
      throw new IllegalArgumentException("Token lifetime must be between 1 and 365 days");
    }

    Instant now = Instant.now();
    if (tokenRepository.countActiveByUser(owner, now) >= MAX_ACTIVE_TOKENS_PER_USER) {
      throw new IllegalArgumentException("Active token limit reached");
    }

    String publicId = newUniquePublicId();
    String raw = PatTokens.format(publicId, PatTokens.newSecret(RANDOM));

    PersonalAccessToken token = new PersonalAccessToken();
    token.setPublicId(publicId);
    token.setUser(owner);
    token.setName(name);
    token.setTokenHash(TokenHashUtil.sha256(raw));
    token.setScopes(scopes);
    token.setCreatedAt(now);
    token.setExpiresAt(now.plus(Duration.ofDays(days)));
    tokenRepository.save(token);

    return new CreatedPersonalAccessTokenDto(raw, PersonalAccessTokenDto.from(token, now));
  }

  public List<PersonalAccessTokenDto> list(User owner) {
    requireInteractiveSession();
    Instant now = Instant.now();
    return tokenRepository.findAllByUserOrderByCreatedAtDesc(owner).stream()
        .map(t -> PersonalAccessTokenDto.from(t, now))
        .toList();
  }

  /** Revokes one of the owner's tokens. Idempotent; unknown or foreign ids yield 404. */
  public PersonalAccessTokenDto revoke(User owner, String publicId) {
    requireInteractiveSession();
    PersonalAccessToken token =
        tokenRepository
            .findByPublicIdAndUser(publicId, owner)
            .orElseThrow(() -> new PersonalAccessTokenNotFoundException("Token not found"));
    Instant now = Instant.now();
    if (token.getRevokedAt() == null) {
      token.setRevokedAt(now);
      tokenRepository.save(token);
    }
    return PersonalAccessTokenDto.from(token, now);
  }

  /** Revokes every non-revoked token of the user. Returns the number revoked. */
  public int revokeAllForUser(User owner) {
    return tokenRepository.revokeAllActiveByUser(owner, Instant.now());
  }

  /**
   * Verifies a raw bearer token. Returns empty for malformed, unknown, mismatched, revoked or
   * expired tokens without distinguishing between them.
   */
  public Optional<PatAuthenticationToken> authenticate(String raw) {
    Optional<String> publicId = PatTokens.parsePublicId(raw);
    if (publicId.isEmpty()) {
      return Optional.empty();
    }
    String incomingHash = TokenHashUtil.sha256(raw);
    Optional<PersonalAccessToken> stored = tokenRepository.findByPublicId(publicId.get());
    boolean hashMatches =
        TokenHashUtil.constantTimeEquals(
            incomingHash, stored.map(PersonalAccessToken::getTokenHash).orElse(DUMMY_HASH));
    Instant now = Instant.now();
    if (stored.isEmpty() || !hashMatches || !stored.get().isActive(now)) {
      return Optional.empty();
    }

    PersonalAccessToken token = stored.get();
    if (!PersonalAccessToken.AUDIENCE_CORE.equals(token.getAudience())) {
      return Optional.empty();
    }
    tokenRepository.touchLastUsed(token.getId(), now, now.minus(LAST_USED_GRANULARITY));

    User owner = token.getUser();
    var principal =
        new org.springframework.security.core.userdetails.User(
            owner.getUsername(), owner.getPassword(), List.of());
    return Optional.of(
        new PatAuthenticationToken(
            principal, token.getId(), token.getPublicId(), owner.getId(), token.getScopes()));
  }

  private static void requireInteractiveSession() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof PatAuthenticationToken) {
      throw new AccessDeniedException("Personal access tokens cannot manage tokens");
    }
  }

  private static Set<PatScope> parseScopes(List<String> values) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("At least one scope is required");
    }
    Set<PatScope> scopes = EnumSet.noneOf(PatScope.class);
    for (String value : values) {
      scopes.add(
          PatScope.fromValue(value)
              .orElseThrow(() -> new IllegalArgumentException("Unsupported scope")));
    }
    return scopes;
  }

  private String newUniquePublicId() {
    for (int i = 0; i < PUBLIC_ID_ATTEMPTS; i++) {
      String candidate = PatTokens.newPublicId(RANDOM);
      if (!tokenRepository.existsByPublicId(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not allocate a unique token id");
  }
}
