package vaultWeb.services.auth;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.models.SecurityEvent;
import vaultWeb.security.annotations.SecurityEventType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;
import org.slf4j.Logger;

import static vaultWeb.services.auth.RefreshTokenCleanupService.log;
private final SecurityEventRepository securityEventRepository;
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

  private final AuthenticationManager authenticationManager;
  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenService refreshTokenService;

  /**
   * Authenticates a user using their username and password and returns a JWT token upon successful
   * authentication.
   *
   * <p>Workflow:
   *
   * <ol>
   *   <li>The AuthenticationManager validates the username and password against the stored hash.
   *   <li>If authentication succeeds, the Authentication object is stored in the SecurityContext.
   *   <li>UserDetails are retrieved from the Authentication object, containing basic security info
   *       (username, roles).
   *   <li>The full User entity is then loaded from the database for additional details.
   *   <li>A JWT token is generated for the user, signed and valid for a specific duration.
   * </ol>
   *
   * <p>Detailed notes on {@code authenticationManager.authenticate(...)}:
   *
   * <ul>
   *   <li>Spring Security calls the UserDetailsService to fetch user info by username.
   *   <li>The provided password is compared with the stored hashed password using PasswordEncoder.
   *   <li>If the password matches, a fully authenticated Authentication object is returned.
   *   <li>If the password does not match, a BadCredentialsException is thrown.
   * </ul>
   *
   * @param username the username provided by the client
   * @param password the plaintext password provided by the client
   * @return a signed JWT token representing the authenticated user
   * @throws UserNotFoundException if the user does not exist in the database
   */
  public LoginResult login(String username, String password) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(username, password));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    UserDetails userDetails = (UserDetails) authentication.getPrincipal();

    User user =
        userRepository
            .findByUsername(userDetails.getUsername())
            .orElseThrow(
                () -> new UserNotFoundException("User not found: " + userDetails.getUsername()));

    String accessToken = jwtUtil.generateToken(user);
    return new LoginResult(user, accessToken);
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
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }

    Object principal = authentication.getPrincipal();
    if (principal instanceof UserDetails userDetails) {
      return userRepository.findByUsername(userDetails.getUsername()).orElse(null);
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
   * @param response HTTP response used to set the rotated refresh token cookie
   * @return a response containing a new access token if the refresh succeeds
   */
  @Transactional
  public ResponseEntity<?> refresh(String rawRefreshToken, HttpServletResponse response) {

    Claims claims;
    try {
      claims = jwtUtil.parseRefreshToken(rawRefreshToken);
    } catch (JwtException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    RefreshToken storedToken = refreshTokenRepository.findByTokenId(tokenId).orElse(null);

    // Case 1: token kabhi tha hi nahi
    if (storedToken == null) {
      log.warn("Refresh failed: unknown tokenId={}", tokenId);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // Case 2: token already revoked tha, phir bhi dobara use hua -> REPLAY ATTACK
    if (storedToken.isRevoked()) {
      User suspectUser = storedToken.getUser();

      log.error(
              "SECURITY ALERT: revoked refresh token reused. tokenId={}, userId={}",
              tokenId, suspectUser.getId());

      // DB mein security event save karo (audit trail)
      SecurityEvent event = new SecurityEvent();
      event.setUser(suspectUser);
      event.setUsername(suspectUser.getUsername());
      event.setEventType(SecurityEventType.TOKEN_REPLAY_DETECTED);
      event.setStatus("FAILURE");
      event.setTimestamp(Instant.now());
      securityEventRepository.save(event);

      // defense-in-depth: is user ke saare active refresh tokens revoke kardo
      refreshTokenRepository.revokeAllByUser(suspectUser.getId());

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // Case 3: token exist karta hai, revoked nahi — normal validation
    String incomingHash = TokenHashUtil.sha256(rawRefreshToken);
    if (!TokenHashUtil.constantTimeEquals(incomingHash, storedToken.getTokenHash())
            || storedToken.getExpiresAt().isBefore(Instant.now())) {

      log.warn("Refresh failed: invalid hash or expired token. tokenId={}", tokenId);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // rotate
    storedToken.setRevoked(true);
    refreshTokenRepository.save(storedToken);

    User user = storedToken.getUser();

    refreshTokenService.create(user, response);

    String newAccessToken = jwtUtil.generateToken(user);

    return ResponseEntity.ok(Map.of("token", newAccessToken));
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
                .findByTokenId(tokenId)
                .ifPresent(
                        token -> {
                          if (token.isRevoked()) {
                            log.warn(
                                    "Logout called with already-revoked token, userId={}, tokenId={}",
                                    token.getUser().getId(),
                                    tokenId);
                          } else {
                            token.setRevoked(true);
                            refreshTokenRepository.save(token);
                          }
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
