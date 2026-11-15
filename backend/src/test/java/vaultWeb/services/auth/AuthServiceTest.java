package vaultWeb.services.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
}
