package vaultWeb.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints and validates short-lived, single-purpose tokens used to stream media through the media
 * proxy.
 *
 * <p><b>Why this exists rather than reusing {@link JwtUtil}:</b> a native {@code <video>}, {@code
 * <audio>} or {@code <img>} tag cannot attach an {@code Authorization} header, so the credential
 * has to travel in the URL. A regular access token must therefore never be used here: URLs leak
 * into browser history, server access logs and {@code Referer} headers, and this application's
 * access tokens are additionally accepted by the cloud-page service (both services are configured
 * with the same {@code jwt.secret}). A leaked access token would grant the holder every endpoint of
 * both services.
 *
 * <p>Tokens issued here are therefore:
 *
 * <ul>
 *   <li>signed with a <b>dedicated key</b> ({@code jwt.mediaSecret}), so they are rejected by
 *       {@link JwtAuthFilter} and confer no general API access;
 *   <li><b>scoped to a single file path</b>, carried in the {@code path} claim and re-checked on
 *       every request, so one token cannot be replayed against another file;
 *   <li><b>short-lived</b> (default 5 minutes), long enough to start playback and seek within a
 *       session but not to be usefully shared.
 * </ul>
 */
@Component
public class MediaTokenService {

  /** Distinguishes media tokens from any other token that might be signed with this key. */
  private static final String PURPOSE_CLAIM = "purpose";

  private static final String PURPOSE_MEDIA = "media";

  private static final String PATH_CLAIM = "path";

  private final SecretKey mediaKey;

  private final long ttlMillis;

  public MediaTokenService(
      @Value("${jwt.mediaSecret}") String mediaSecret,
      @Value("${app.media.token-ttl-seconds:300}") long ttlSeconds) {
    this.mediaKey = Keys.hmacShaKeyFor(mediaSecret.getBytes());
    this.ttlMillis = ttlSeconds * 1000;
  }

  /** Seconds until an issued token expires; returned to the client so it can refresh in time. */
  public long getTtlSeconds() {
    return ttlMillis / 1000;
  }

  /**
   * Issues a media token for one specific file belonging to one specific user.
   *
   * @param username the authenticated user the token acts on behalf of
   * @param path the user-root-relative file path this token is valid for
   * @return a signed, short-lived media token
   */
  public String generateToken(String username, String path) {
    Instant now = Instant.now();
    return Jwts.builder()
        .setSubject(username)
        .claim(PURPOSE_CLAIM, PURPOSE_MEDIA)
        .claim(PATH_CLAIM, path)
        .setIssuedAt(Date.from(now))
        .setExpiration(new Date(now.toEpochMilli() + ttlMillis))
        .signWith(mediaKey, SignatureAlgorithm.HS256)
        .compact();
  }

  /**
   * Validates a media token and returns its claims.
   *
   * <p>Signature, expiry and the {@code purpose} claim are all checked. The purpose check means
   * that even if another token type were ever signed with this key, it could not be used to stream
   * files.
   *
   * @param token the token from the request path
   * @return the parsed claims
   * @throws JwtException if the token is invalid, expired, or not a media token
   */
  public Claims parseToken(String token) {
    Claims claims =
        Jwts.parserBuilder().setSigningKey(mediaKey).build().parseClaimsJws(token).getBody();

    if (!PURPOSE_MEDIA.equals(claims.get(PURPOSE_CLAIM, String.class))) {
      throw new JwtException("Token is not a media token");
    }
    return claims;
  }

  /** Extracts the file path a media token is scoped to. */
  public String extractPath(Claims claims) {
    return claims.get(PATH_CLAIM, String.class);
  }
}
