package vaultWeb.services.auth;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletResponse;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private AuthenticationManager authenticationManager;

  @Mock private JwtUtil jwtUtil;

  @Mock private UserRepository userRepository;

  @Mock private RefreshTokenRepository refreshTokenRepository;

  @Mock private RefreshTokenService refreshTokenService;

  @InjectMocks private AuthService authService;

  private User createUser(String username, String password) {
    User user = new User();
    user.setUsername(username);
    user.setPassword(password);
    return user;
  }

  @Test
  void shouldLoginSuccessfully() {
    User user = createUser("testuser", "hashedPwd");
    Authentication authentication = mock(Authentication.class);

    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(authentication);
    when(authentication.getPrincipal())
        .thenReturn(
            org.springframework.security.core.userdetails.User.withUsername("testuser")
                .password("hashedPwd")
                .authorities("ROLE_USER")
                .build());
    when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
    when(jwtUtil.generateToken(user)).thenReturn("jwt-token");

    LoginResult result = authService.login("testuser", "password");

    assertNotNull(result);
    assertEquals("testuser", result.user().getUsername());
    assertEquals("jwt-token", result.accessToken());
    verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
  }

  @Test
  void shouldFailLogin_WhenUserNotFound() {
    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenThrow(new BadCredentialsException("Bad credentials"));

    assertThrows(BadCredentialsException.class, () -> authService.login("unknown", "password"));
  }

  @Test
  void shouldFailLogin_WhenPasswordIncorrect() {
    when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenThrow(new BadCredentialsException("Bad credentials"));

    assertThrows(BadCredentialsException.class, () -> authService.login("testuser", "wrong"));
  }

  // ---------------------------------------------------------------------
  // Refresh token replay detection tests (Issue #260)
  // ---------------------------------------------------------------------

  @Test
  void shouldDetectReplay_WhenRevokedTokenIsReused() {
    String tokenId = "jti-123";
    Long userId = 42L;

    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn(tokenId);
    when(jwtUtil.parseRefreshToken("stolen-token")).thenReturn(claims);

    User user = createUser("victim", "hashedPwd");
    user.setId(userId);

    RefreshToken revokedToken = mock(RefreshToken.class);
    when(revokedToken.isRevoked()).thenReturn(true);
    when(revokedToken.getUser()).thenReturn(user);

    when(refreshTokenRepository.findByTokenId(tokenId)).thenReturn(Optional.of(revokedToken));

    HttpServletResponse response = mock(HttpServletResponse.class);

    ResponseEntity<?> result = authService.refresh("stolen-token", response);

    assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
    // Defense-in-depth: all sessions for that user must be revoked
    verify(refreshTokenRepository).revokeAllByUser(userId);
    // Rotation must NOT happen for a replayed token
    verify(refreshTokenRepository, never()).save(any());
    verify(refreshTokenService, never()).create(any(), any());
  }

  @Test
  void shouldReturnUnauthorized_WhenTokenIdDoesNotExist() {
    String tokenId = "jti-never-existed";

    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn(tokenId);
    when(jwtUtil.parseRefreshToken("bogus-token")).thenReturn(claims);

    when(refreshTokenRepository.findByTokenId(tokenId)).thenReturn(Optional.empty());

    HttpServletResponse response = mock(HttpServletResponse.class);

    ResponseEntity<?> result = authService.refresh("bogus-token", response);

    assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
    // No user to revoke sessions for — must not be called
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void shouldRotateSuccessfully_WhenTokenIsValidAndNotRevoked() {
    String tokenId = "jti-valid";
    String rawToken = "valid-token";

    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn(tokenId);
    when(jwtUtil.parseRefreshToken(rawToken)).thenReturn(claims);

    User user = createUser("legituser", "hashedPwd");
    user.setId(1L);

    RefreshToken validToken = mock(RefreshToken.class);
    when(validToken.isRevoked()).thenReturn(false);
    when(validToken.getUser()).thenReturn(user);
    when(validToken.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
    when(validToken.getTokenHash())
        .thenReturn(vaultWeb.security.TokenHashUtil.sha256(rawToken));

    when(refreshTokenRepository.findByTokenId(tokenId)).thenReturn(Optional.of(validToken));
    when(jwtUtil.generateToken(user)).thenReturn("new-access-token");

    HttpServletResponse response = mock(HttpServletResponse.class);

    ResponseEntity<?> result = authService.refresh(rawToken, response);

    assertEquals(HttpStatus.OK, result.getStatusCode());
    verify(validToken).setRevoked(true);
    verify(refreshTokenRepository).save(validToken);
    verify(refreshTokenService).create(user, response);
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
  }
}