package vaultWeb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaTokenServiceTest {

  private static final String MEDIA_SECRET =
      "test-media-secret-must-be-at-least-32-bytes-long-for-hs256";

  private MediaTokenService mediaTokenService;

  @BeforeEach
  void setUp() {
    // 300-second TTL matches the application.properties default; individual tests
    // that care about expiry build their own short-lived tokens directly.
    mediaTokenService = new MediaTokenService(MEDIA_SECRET, 300);
  }

  @Test
  void generateToken_thenParseToken_roundTripsSubjectAndPath() {
    String token = mediaTokenService.generateToken("Ted", "/videos/clip.mp4");

    Claims claims = mediaTokenService.parseToken(token);

    assertThat(claims.getSubject()).isEqualTo("Ted");
    assertThat(mediaTokenService.extractPath(claims)).isEqualTo("/videos/clip.mp4");
  }

  @Test
  void parseToken_expiredToken_throwsJwtException() {
    // A token minted with a TTL of 0 is already expired by the time it's parsed.
    MediaTokenService shortLived = new MediaTokenService(MEDIA_SECRET, 0);
    String token = shortLived.generateToken("Ted", "/videos/clip.mp4");

    assertThatThrownBy(() -> shortLived.parseToken(token)).isInstanceOf(ExpiredJwtException.class);
  }

  @Test
  void parseToken_signedWithDifferentKey_throwsJwtException() {
    MediaTokenService otherService =
        new MediaTokenService("a-completely-different-secret-key-32-bytes-min", 300);
    String token = otherService.generateToken("Ted", "/videos/clip.mp4");

    assertThatThrownBy(() -> mediaTokenService.parseToken(token)).isInstanceOf(JwtException.class);
  }

  /**
   * Guards the exact vulnerability this class exists to prevent: jwt.secret is shared between
   * vault-web and cloud-page, so a regular access token minted by {@link JwtUtil} must never be
   * accepted here. If it were, a media URL would double as a bearer token for every endpoint in
   * both services.
   */
  @Test
  void parseToken_regularAccessTokenSignedWithDifferentKey_isRejected() {
    // Simulates a JwtUtil-style access token: same shape, but signed with jwt.secret rather
    // than jwt.mediaSecret, and carrying no "purpose" claim at all.
    SecretKey accessKey =
        Keys.hmacShaKeyFor("a-totally-unrelated-jwt-secret-key-32-bytes".getBytes());
    String accessToken =
        Jwts.builder()
            .setSubject("Ted")
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(accessKey, SignatureAlgorithm.HS256)
            .compact();

    assertThatThrownBy(() -> mediaTokenService.parseToken(accessToken))
        .isInstanceOf(JwtException.class);
  }

  /**
   * Even a token that happens to be signed with the correct media key must still declare
   * purpose=media. This is what stops the key from silently being reused for some other token type
   * in the future without this class noticing.
   */
  @Test
  void parseToken_correctKeyButWrongPurpose_throwsJwtException() {
    SecretKey mediaKey = Keys.hmacShaKeyFor(MEDIA_SECRET.getBytes());
    String wrongPurposeToken =
        Jwts.builder()
            .setSubject("Ted")
            .claim("purpose", "not-media")
            .claim("path", "/videos/clip.mp4")
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(mediaKey, SignatureAlgorithm.HS256)
            .compact();

    assertThatThrownBy(() -> mediaTokenService.parseToken(wrongPurposeToken))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("not a media token");
  }

  @Test
  void getTtlSeconds_returnsConfiguredValue() {
    assertThat(mediaTokenService.getTtlSeconds()).isEqualTo(300);
  }

  @Test
  void generateToken_differentPaths_produceTokensScopedToTheirOwnPath() {
    String tokenA = mediaTokenService.generateToken("Ted", "/a.mp4");
    String tokenB = mediaTokenService.generateToken("Ted", "/b.mp4");

    assertThat(mediaTokenService.extractPath(mediaTokenService.parseToken(tokenA)))
        .isEqualTo("/a.mp4");
    assertThat(mediaTokenService.extractPath(mediaTokenService.parseToken(tokenB)))
        .isEqualTo("/b.mp4");
  }
}
