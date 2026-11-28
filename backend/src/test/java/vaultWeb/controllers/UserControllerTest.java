package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import vaultWeb.dtos.user.ChangePasswordRequest;
import vaultWeb.dtos.user.LoginRequest;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.dtos.user.UserResponseDto;
import vaultWeb.exceptions.DuplicateUsernameException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.User;
import vaultWeb.services.UserService;
import vaultWeb.services.auth.AuthService;
import vaultWeb.services.auth.LoginResult;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UserService userService;

  @Mock private AuthService authService;

  @InjectMocks private UserController userController;

  // ============================================================================
  // Test Data Helper Methods
  // ============================================================================

  /**
   * Creates a test User object with the given ID and username.
   *
   * @param id the user ID
   * @param username the username
   * @return a User object for testing
   */
  private User createTestUser(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setPassword("hashedPassword123");
    return user;
  }

  @Test
  void shouldRegisterUserSuccessfully() {
    UserDto userDto = new UserDto("testuser", "password");
    doNothing().when(userService).registerUser(any(User.class));
    ResponseEntity<String> response = userController.register(userDto);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("User registered successfully", response.getBody());
    verify(userService, times(1)).registerUser(any(User.class));
  }

  @Test
  void shouldLoginSuccessfully() {
    LoginRequest loginRequest = new LoginRequest("testuser", "password");
    when(authService.login(loginRequest.getUsername(), loginRequest.getPassword()))
        .thenReturn(
            new LoginResult(
                "mock-jwt-token", ResponseCookie.from("refresh_token", "mock").build()));
    ResponseEntity<?> response = userController.login(loginRequest);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("mock-jwt-token", ((Map<String, String>) response.getBody()).get("token"));
    verify(authService, times(1)).login(loginRequest.getUsername(), loginRequest.getPassword());
  }

  @Test
  void shouldRefreshTokenSuccessfully() {
    String refreshToken = "mock-refresh-token";
    when(authService.refresh(refreshToken))
        .thenAnswer(invocation -> ResponseEntity.ok(Map.of("token", "mock-jwt-token")));
    ResponseEntity<?> response = userController.refresh(refreshToken);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("mock-jwt-token", ((Map<String, String>) response.getBody()).get("token"));
    verify(authService, times(1)).refresh(refreshToken);
  }

  @Test
  void shouldLogoutSuccessfully() {
    String refreshToken = "mock-refresh-token";
    HttpServletResponse responseMock = mock(HttpServletResponse.class);
    doNothing().when(authService).logout(refreshToken, responseMock);
    ResponseEntity<?> response = userController.logout(refreshToken, responseMock);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(authService, times(1)).logout(refreshToken, responseMock);
  }

  @Test
  void shouldCheckUsernameExists_WhenExists() {

    String username = "existingUser";
    when(userService.usernameExists(username)).thenReturn(true);
    ResponseEntity<Map<String, Boolean>> response = userController.checkUsernameExists(username);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(true, (response.getBody()).get("exists"));
    verify(userService, times(1)).usernameExists(username);
  }

  @Test
  void shouldCheckUsernameExists_WhenNotExists() {
    String username = "nonExistingUser";
    when(userService.usernameExists(username)).thenReturn(false);
    ResponseEntity<Map<String, Boolean>> response = userController.checkUsernameExists(username);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(false, (response.getBody()).get("exists"));
    verify(userService, times(1)).usernameExists(username);
  }

  @Test
  void shouldGetAllUsersSuccessfully() {
    List<User> testUsers = List.of(createTestUser(1L, "user1"), createTestUser(2L, "user2"));
    when(userService.getAllUsers()).thenReturn(testUsers);
    ResponseEntity<List<UserResponseDto>> response = userController.getAllUsers();
    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<UserResponseDto> users = response.getBody();
    assertEquals(testUsers.size(), users.size());
    verify(userService, times(1)).getAllUsers();
    assertEquals(testUsers.get(0).getUsername(), users.get(0).getUsername());
    assertEquals(testUsers.get(1).getUsername(), users.get(1).getUsername());
  }

  @Test
  void shouldChangePasswordSuccessfully() {
    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword("currentPassword");
    request.setNewPassword("newPassword");
    ResponseCookie cookie = ResponseCookie.from("refresh_token", "replacement").build();
    when(authService.changePassword(request.getCurrentPassword(), request.getNewPassword()))
        .thenReturn(new LoginResult("replacement-access-token", cookie));

    ResponseEntity<Map<String, String>> response = userController.changePassword(request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("replacement-access-token", response.getBody().get("token"));
    assertEquals(cookie.toString(), response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
  }

  // ============================================================================
  // Error Path Tests (6 tests)
  // ============================================================================

  @Test
  void shouldFailRegistration_WhenDuplicateUsername() {
    UserDto userDto = new UserDto("duplicateuser", "password");
    doThrow(new DuplicateUsernameException("Username already exists"))
        .when(userService)
        .registerUser(any(User.class));
    assertThrows(DuplicateUsernameException.class, () -> userController.register(userDto));
    verify(userService, times(1)).registerUser(any(User.class));
  }

  @Test
  void shouldFailLogin_WhenInvalidCredentials() {
    LoginRequest loginRequest = new LoginRequest("testuser", "wrongpassword");

    // Mock: authService.login() throws BadCredentialsException
    when(authService.login(loginRequest.getUsername(), loginRequest.getPassword()))
        .thenThrow(new BadCredentialsException("Invalid credentials"));
    // Act & Assert: Verify the exception is thrown (GlobalExceptionHandler handles it in real app)
    assertThrows(BadCredentialsException.class, () -> userController.login(loginRequest));
  }

  @Test
  void shouldFailRefresh_WhenNoTokenProvided() {
    String refreshToken = null;
    ResponseEntity<?> response = userController.refresh(refreshToken);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    verify(authService, never()).refresh(any());
  }

  @Test
  void shouldFailRefresh_WhenInvalidToken() {
    String invalidToken = "invalid-token";
    when(authService.refresh(invalidToken))
        .thenReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    ResponseEntity<?> response = userController.refresh(invalidToken);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    verify(authService, times(1)).refresh(invalidToken);
  }

  @Test
  void shouldFailChangePassword_WhenRejectedByAuthService() {
    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword("wrongPassword");
    request.setNewPassword("newPassword");
    when(authService.changePassword(request.getCurrentPassword(), request.getNewPassword()))
        .thenThrow(new UnauthorizedException("Current password is incorrect"));

    assertThrows(UnauthorizedException.class, () -> userController.changePassword(request));
  }
}
