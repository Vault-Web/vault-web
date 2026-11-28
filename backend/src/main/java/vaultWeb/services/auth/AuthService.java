package vaultWeb.services.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.UserService;

/**
 * Service class responsible for handling authentication and user session-related operations.
 *
 * <p>Provides functionality for:
 *
 * <ul>
 *   <li>Authenticating users with username and password.
 *   <li>Generating JWT tokens for authenticated users.
 *   <li>Retrieving the currently authenticated user from the security context.
 * </ul>
 *
 * <p>This service integrates with Spring Security's AuthenticationManager for authentication,
 * UserRepository for fetching user entities, and JwtUtil for generating JWT tokens.
 *
 * <p>Security considerations:
 *
 * <ul>
 *   <li>Passwords are never stored or transmitted in plaintext.
 *   <li>Authentication uses BCryptPasswordEncoder for secure password hashing.
 *   <li>JWT tokens are signed and include necessary claims (e.g., username, role) for stateless
 *       authentication.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final AuthenticationManager authenticationManager;
  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenService refreshTokenService;
  private final SecurityEventRepository securityEventRepository;
  private final UserService userService;

  /** Checks the current browser session for an operation that does not rotate the token. */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public boolean hasActiveRefreshSession(String token, String username) {
    if (token == null || token.isBlank()) return false;
    String tokenId;
    try {
      tokenId = jwtUtil.parseRefreshToken(token).getId();
    } catch (JwtException e) {
      return false;
    }
    if (tokenId == null || tokenId.isBlank()) return false;
    return refreshTokenRepository
        .findByTokenIdAndRevokedFalse(tokenId)
        .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
        .filter(session -> session.getUser().getUsername().equals(username))
        .filter(
            session ->
                TokenHashUtil.constantTimeEquals(
                    TokenHashUtil.sha256(token), session.getTokenHash()))
        .isPresent();
  }

  /**
   * Validates the credentials and issues a fresh access token and refresh cookie.
   *
   * <p>Holds the account row lock across authentication and session issuance so a password change
   * cannot leave behind a session issued against the old password.
   */
  @Transactional
  public LoginResult login(String username, String password) {
    // Serialize password checks and session issuance with account password changes.
    User user = userRepository.findByUsernameForUpdate(username).orElse(null);
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(username, password));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    if (user == null) {
      throw new UserNotFoundException("User not found: " + username);
    }
    return issueSession(user);
  }

  @Transactional
  public LoginResult changePassword(String currentPassword, String newPassword) {
    String username = getCurrentUsername();
    if (username == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    User user =
        userRepository
            .findByUsernameForUpdate(username)
            .orElseThrow(() -> new UnauthorizedException("User not authenticated"));
    userService.changePassword(user, currentPassword, newPassword);
    return issueSession(user);
  }

  private LoginResult issueSession(User user) {
    return new LoginResult(jwtUtil.generateToken(user), refreshTokenService.create(user));
  }

  /**
   * Retrieves the currently authenticated user from the SecurityContext.
   *
   * <p>If no user is authenticated, this method returns {@code null}. Otherwise, it fetches the
   * full {@link User} entity from the database based on the username.
   *
   * @return the currently authenticated {@link User}, or {@code null} if no user is authenticated
   */
  public User getCurrentUser() {
    String username = getCurrentUsername();
    return username == null ? null : userRepository.findByUsername(username).orElse(null);
  }

  private String getCurrentUsername() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }

    Object principal = authentication.getPrincipal();
    if (principal instanceof UserDetails userDetails) {
      return userDetails.getUsername();
    }

    return null;
  }

  /**
   * Refreshes the access token using a valid refresh token and performs refresh token rotation.
   *
   * <p><b>Workflow:</b>
   *
   * <ol>
   *   <li>Parses and verifies the refresh JWT using the refresh signing key, including signature
   *       and expiration validation.
   *   <li>Extracts the token identifier (<code>jti</code>) from the refresh token.
   *   <li>Looks up the corresponding refresh token record in the database using the extracted
   *       <code>jti</code>.
   *   <li>Verifies the refresh token by comparing the SHA-256 hash of the provided token with the
   *       stored hash.
   *   <li>If valid, revokes the existing refresh token to prevent reuse (refresh token rotation).
   *   <li>Issues a new refresh token, stores its hash in the database, and sends it to the client
   *       as a secure, HttpOnly cookie.
   *   <li>Generates and returns a new short-lived access token.
   * </ol>
   *
   * <p><b>Security considerations:</b>
   *
   * <ul>
   *   <li>Refresh tokens are JWTs signed with a dedicated refresh signing key.
   *   <li>Only a non-secret identifier (<code>jti</code>) is used for database lookup; the refresh
   *       token itself is never stored in plaintext.
   *   <li>Refresh tokens are stored using a one-way SHA-256 hash.
   *   <li>Rotation ensures stolen refresh tokens cannot be reused.
   *   <li>Revoked tokens may be retained temporarily to allow replay-attack detection and auditing.
   * </ul>
   *
   * <p><b>Error scenarios:</b>
   *
   * <ul>
   *   <li>{@code 401 Unauthorized} if the refresh token is missing, expired, revoked, invalid, or
   *       reused.
   * </ul>
   *
   * @param rawRefreshToken the refresh JWT provided by the client (via HttpOnly cookie)
   * @return a response containing a new access token if the refresh succeeds
   */
  @Transactional
  public ResponseEntity<?> refresh(String rawRefreshToken) {

    Claims claims;
    Long userId;
    try {
      claims = jwtUtil.parseRefreshToken(rawRefreshToken);
      userId = Long.valueOf(claims.getSubject());
    } catch (JwtException | NumberFormatException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String tokenId = claims.getId();
    if (tokenId == null || tokenId.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    User user = userRepository.findByIdForUpdate(userId).orElse(null);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    RefreshToken storedToken = refreshTokenRepository.findByTokenId(tokenId).orElse(null);

    if (storedToken == null || !userId.equals(storedToken.getUser().getId())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String incomingHash = TokenHashUtil.sha256(rawRefreshToken);
    if (!TokenHashUtil.constantTimeEquals(incomingHash, storedToken.getTokenHash())
        || storedToken.getExpiresAt().isBefore(Instant.now())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    if (storedToken.isRevoked()) {
      User suspectUser = storedToken.getUser();
      log.warn("Revoked refresh token reused for user {}", suspectUser.getId());
      SecurityEvent event = new SecurityEvent();
      event.setUser(suspectUser);
      event.setUsername(suspectUser.getUsername());
      event.setEventType(SecurityEventType.TOKEN_REPLAY_DETECTED);
      event.setStatus("FAILURE");
      event.setTimestamp(Instant.now());
      securityEventRepository.save(event);
      // Concurrent tabs can legitimately submit the same token during rotation.
      // Reject the replay without revoking unrelated active sessions.
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    LoginResult session = issueSession(user);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, session.refreshCookie().toString())
        .body(Map.of("token", session.accessToken()));
  }

  /**
   * Logs out the current session by revoking the active refresh token (identified via its jti) and
   * deleting the refresh token cookie.
   *
   * <p>This ensures the refresh token cannot be reused even if it was previously leaked or stolen.
   */
  @Transactional
  public void logout(String rawRefreshToken, HttpServletResponse response) {

    if (rawRefreshToken != null) {
      try {
        String tokenId = jwtUtil.extractTokenId(rawRefreshToken);

        refreshTokenRepository
            .findByTokenIdAndRevokedFalse(tokenId)
            .ifPresent(
                token -> {
                  token.setRevoked(true);
                  refreshTokenRepository.save(token);
                });

      } catch (JwtException ignored) {
        // Token already invalid / expired — nothing to revoke
      }
    }

    // Always delete cookie (even if token was invalid)
    ResponseCookie deleteCookie =
        ResponseCookie.from("refresh_token", "")
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path("/api/auth/refresh")
            .maxAge(0)
            .build();

    response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie.toString());
  }
}
