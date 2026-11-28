package vaultWeb.services.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import vaultWeb.models.RefreshToken;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.TokenHashUtil;
import vaultWeb.security.annotations.SecurityEventType;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private AuthenticationManager authenticationManager;

  @Mock private JwtUtil jwtUtil;

  @Mock private UserRepository userRepository;

  @Mock private RefreshTokenRepository refreshTokenRepository;

  @Mock private SecurityEventRepository securityEventRepository;

  @Mock private RefreshTokenService refreshTokenService;

  @InjectMocks private AuthService authService;

  @AfterEach
  void clearSecurityContext() {
    // login stores the mocked Authentication in thread-local state shared with later tests.
    SecurityContextHolder.clearContext();
  }

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
    when(userRepository.findByUsernameForUpdate("testuser")).thenReturn(Optional.of(user));
    when(jwtUtil.generateToken(user)).thenReturn("jwt-token");

    LoginResult result = authService.login("testuser", "password");

    assertNotNull(result);
    assertEquals("jwt-token", result.accessToken());
    verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    verify(refreshTokenService).create(user);
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

  @Test
  void revokedRefreshTokenRecordsReplayWithoutRotatingOtherSessions() {
    String rawToken = "signed-refresh-token";
    User user = createUser("testuser", "hashedPwd");
    user.setId(7L);
    RefreshToken stored = new RefreshToken();
    stored.setTokenId("token-id");
    stored.setTokenHash(TokenHashUtil.sha256(rawToken));
    stored.setExpiresAt(Instant.now().plusSeconds(3600));
    stored.setRevoked(true);
    stored.setUser(user);
    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn("token-id");
    when(claims.getSubject()).thenReturn("7");
    when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    when(jwtUtil.parseRefreshToken(rawToken)).thenReturn(claims);
    when(refreshTokenRepository.findByTokenId("token-id")).thenReturn(Optional.of(stored));

    var result = authService.refresh(rawToken);

    assertEquals(401, result.getStatusCode().value());
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
    ArgumentCaptor<SecurityEvent> event = ArgumentCaptor.forClass(SecurityEvent.class);
    verify(securityEventRepository).save(event.capture());
    assertEquals(SecurityEventType.TOKEN_REPLAY_DETECTED, event.getValue().getEventType());
    assertEquals(user, event.getValue().getUser());
    verify(refreshTokenService, never()).create(any());
  }

  @Test
  void mismatchedRefreshTokenHashDoesNotTriggerReplayResponse() {
    String rawToken = "signed-refresh-token";
    User user = createUser("testuser", "hashedPwd");
    user.setId(7L);
    RefreshToken stored = new RefreshToken();
    stored.setUser(user);
    stored.setTokenHash(TokenHashUtil.sha256("different-token"));
    stored.setExpiresAt(Instant.now().plusSeconds(3600));
    stored.setRevoked(true);
    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn("token-id");
    when(claims.getSubject()).thenReturn("7");
    when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    when(jwtUtil.parseRefreshToken(rawToken)).thenReturn(claims);
    when(refreshTokenRepository.findByTokenId("token-id")).thenReturn(Optional.of(stored));

    var result = authService.refresh(rawToken);

    assertEquals(401, result.getStatusCode().value());
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
    verifyNoInteractions(securityEventRepository);
  }
}
