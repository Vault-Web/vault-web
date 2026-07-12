package vaultWeb.services.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  public void create(User user, HttpServletResponse response) {

    refreshTokenRepository.revokeAllByUser(
        user.getId(), RefreshToken.RevokeReason.REPLACED_BY_NEW_LOGIN);

    String tokenId = UUID.randomUUID().toString();

    String refreshToken = jwtUtil.generateRefreshToken(user, tokenId);

    String hash = TokenHashUtil.sha256(refreshToken);

    RefreshToken entity = new RefreshToken();
    entity.setTokenId(tokenId);
    entity.setUser(user);
    entity.setTokenHash(hash);
    entity.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setRevoked(false);

    refreshTokenRepository.save(entity);

    ResponseCookie cookie =
        ResponseCookie.from("refresh_token", refreshToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path("/api/auth/refresh")
            .maxAge(Duration.ofDays(30))
            .build();

    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}