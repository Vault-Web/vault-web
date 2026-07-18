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
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;

/**
 * Service class responsible for handling authentication and user session-related operations.
 *
 * <p>Provides functionality for: - Authenticating users with username and password. - Generating
 * JWT tokens for authenticated users. - Retrieving the currently authenticated user from the
 * security context.
 *
 * <p>Security considerations: - Passwords are never stored or transmitted in plaintext. -
 * Authentication uses BCryptPasswordEncoder for secure password hashing. - JWT tokens are signed
 * and include necessary claims (e.g., username, role) for stateless authentication.
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

  /**
   * Authenticates a user using their username and password and returns a JWT token upon successful
   * authentication.
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

  /** Retrieves the currently authenticated user from the SecurityContext. */
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

  /** Refreshes the access token using a valid refresh token and performs refresh token rotation. */
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
      log.warn(
          "SECURITY_ALERT: refresh token replay detected. userId={}, tokenId={}, timestamp={}",
          storedToken.getUser().getId(),
          tokenId,
          Instant.now());

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String incomingHash = TokenHashUtil.sha256(rawRefreshToken);
    if (!TokenHashUtil.constantTimeEquals(incomingHash, storedToken.getTokenHash())
        || storedToken.getExpiresAt().isBefore(Instant.now())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    storedToken.setRevoked(true);
    refreshTokenRepository.save(storedToken);

    User user = storedToken.getUser();
    refreshTokenService.create(user, response);

    String newAccessToken = jwtUtil.generateToken(user);
    return ResponseEntity.ok(Map.of("token", newAccessToken));
  }

  /**
   * Logs out the current session by revoking the active refresh token and deleting the refresh
   * token cookie.
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
