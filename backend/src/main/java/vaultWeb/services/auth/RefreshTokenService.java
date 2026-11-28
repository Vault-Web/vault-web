package vaultWeb.services.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtUtil jwtUtil;

  /**
   * Creates and issues a new refresh token for the given user.
   *
   * <p><b>Session model:</b>
   *
   * <p>This implementation enforces a <b>single active session per user</b>. Whenever a new refresh
   * token is issued, all previously issued refresh tokens for the user are revoked. This design
   * prioritizes security over multi-device support by preventing concurrent sessions.
   *
   * <p><b>Workflow:</b>
   *
   * <ol>
   *   <li>Revokes all existing refresh tokens associated with the user to ensure only one active
   *       refresh token exists per user/session.
   *   <li>Generates a unique token identifier (<code>jti</code>) for the new refresh token.
   *   <li>Creates a signed refresh token JWT containing the user identifier and the generated
   *       <code>jti</code>.
   *   <li>Computes a one-way SHA-256 hash of the refresh token and stores it in the database
   *       instead of storing the token in plaintext.
   *   <li>Persists the refresh token metadata (jti, hash, user, expiration, and revocation status)
   *       in the database.
   *   <li>Returns the refresh token as a secure, HttpOnly cookie.
   * </ol>
   *
   * <p><b>Security considerations:</b>
   *
   * <ul>
   *   <li>Refresh tokens are JWTs signed with a dedicated refresh signing key.
   *   <li>The refresh token itself is never stored in plaintext; only a SHA-256 hash is persisted.
   *   <li>Revoking existing tokens prevents reuse and enforces refresh token rotation semantics.
   *   <li>The refresh token cookie is marked HttpOnly and Secure to mitigate XSS and
   *       man-in-the-middle attacks.
   * </ul>
   *
   * @param user the authenticated user for whom the refresh token is issued
   * @return the cookie to send once the transaction commits
   */
  public ResponseCookie create(User user) {

    // revoke old tokens
    refreshTokenRepository.revokeAllByUser(user.getId());

    // generate jti
    String tokenId = UUID.randomUUID().toString();

    // generate JWT refresh token
    String refreshToken = jwtUtil.generateRefreshToken(user, tokenId);

    String hash = TokenHashUtil.sha256(refreshToken);

    // store hashed token
    RefreshToken entity = new RefreshToken();
    entity.setTokenId(tokenId);
    entity.setUser(user);
    entity.setTokenHash(hash);
    entity.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setRevoked(false);

    refreshTokenRepository.save(entity);

    return ResponseCookie.from("refresh_token", refreshToken)
        .httpOnly(true)
        .secure(true)
        .sameSite("None")
        .path("/api/auth/refresh")
        .maxAge(Duration.ofDays(30))
        .build();
  }
}
