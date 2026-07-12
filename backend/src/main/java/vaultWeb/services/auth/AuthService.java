package vaultWeb.services.auth;

import java.time.Instant;
import java.util.Map;

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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;

@Service
@RequiredArgsConstructor
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final AuthenticationManager authenticationManager;
  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenService refreshTokenService;

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

  @Transactional
  public ResponseEntity<?> refresh(String rawRefreshToken, HttpServletResponse response) {

    Claims claims;
    try {
      claims = jwtUtil.parseRefreshToken(rawRefreshToken);
    } catch (JwtException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String tokenId = claims.getId();

    RefreshToken storedToken = refreshTokenRepository.findByTokenId(tokenId).orElse(null);

    if (storedToken == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    if (storedToken.isRevoked()) {
      if (storedToken.getRevokeReason() == RefreshToken.RevokeReason.ROTATED) {
        log.warn(
            "SECURITY: Refresh token replay detected — tokenId={}, userId={}",
            tokenId,
            storedToken.getUser().getId());

        refreshTokenRepository.revokeAllByUser(
            storedToken.getUser().getId(), RefreshToken.RevokeReason.ROTATED);
      }

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String incomingHash = TokenHashUtil.sha256(rawRefreshToken);
    if (!TokenHashUtil.constantTimeEquals(incomingHash, storedToken.getTokenHash())
        || storedToken.getExpiresAt().isBefore(Instant.now())) {

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    storedToken.setRevoked(true);
    storedToken.setRevokeReason(RefreshToken.RevokeReason.ROTATED);
    refreshTokenRepository.save(storedToken);

    User user = storedToken.getUser();

    refreshTokenService.create(user, response);

    String newAccessToken = jwtUtil.generateToken(user);

    return ResponseEntity.ok(Map.of("token", newAccessToken));
  }

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
                  token.setRevokeReason(RefreshToken.RevokeReason.LOGOUT);
                  refreshTokenRepository.save(token);
                });

      } catch (JwtException ignored) {
        // Token already invalid / expired — nothing to revoke
      }
    }

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