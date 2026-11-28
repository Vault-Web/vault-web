package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vaultWeb.dtos.user.ChangePasswordRequest;
import vaultWeb.dtos.user.LoginRequest;
import vaultWeb.dtos.user.SecurityEventDto;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.dtos.user.UserResponseDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.User;
import vaultWeb.security.annotations.ApiRateLimit;
import vaultWeb.security.annotations.AuditSecurityEvent;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.ProfilePictureService;
import vaultWeb.services.UserService;
import vaultWeb.services.auth.AuthService;
import vaultWeb.services.auth.LoginResult;
import vaultWeb.services.auth.RefreshTokenService;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "User Controller", description = "Handles registration and login of users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final AuthService authService;
  private final RefreshTokenService refreshTokenService;
  // Injected automatically by Spring — handles file validation and disk storage
  private final ProfilePictureService profilePictureService;

  @PostMapping("/register")
  @Operation(
      summary = "Register a new user",
      description =
          """
                            Accepts a JSON object containing username and plaintext password.
                            The password is hashed using BCrypt (via Spring Security's PasswordEncoder) before being persisted.
                            The new user is assigned the default role 'User'.""")
  @AuditSecurityEvent(SecurityEventType.REGISTER)
  @ApiRateLimit(capacity = 5, refillTokens = 5, refillDurationMinutes = 1, useIpAddress = true)
  @ApiResponse(responseCode = "200", description = "User registered successfully")
  @ApiResponse(
      responseCode = "400",
      description =
          "Password does not meet requirements. It must contain at least one uppercase letter and one digit.")
  @ApiResponse(responseCode = "409", description = "Username already exists.")
  public ResponseEntity<String> register(@Valid @RequestBody UserDto user) {
    userService.registerUser(new User(user));
    return ResponseEntity.ok("User registered successfully");
  }

  @Operation(
      summary = "Authenticate user and issue access & refresh tokens",
      description =
          """
                    Authenticates a user using username and password.

                    On successful authentication:
                    - A short-lived access token (JWT) is returned in the response body.
                    - A long-lived refresh token (JWT) is issued and stored in an HttpOnly, secure cookie.

                    Security details:
                    - Credentials are validated using Spring Security's AuthenticationManager.
                    - Access tokens are stateless and short-lived.
                    - Refresh tokens are JWTs containing a unique identifier (jti), stored hashed in the database.
                    - Refresh tokens are rotated on use and can be revoked server-side.

                    The access token should be sent in the Authorization header for protected endpoints.
                    """)
  @AuditSecurityEvent(SecurityEventType.LOGIN)
  @ApiRateLimit(capacity = 5, refillTokens = 5, refillDurationMinutes = 1, useIpAddress = true)
  @PostMapping("/login")
  @ApiResponse(responseCode = "200", description = "Login successful.")
  @ApiResponse(responseCode = "401", description = "Username or password is incorrect.")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest loginRequest, HttpServletResponse response) {
    LoginResult res = authService.login(loginRequest.getUsername(), loginRequest.getPassword());
    refreshTokenService.create(res.user(), response);
    return ResponseEntity.ok(Map.of("token", res.accessToken()));
  }

  @Operation(
      summary = "Refresh access token using refresh token rotation",
      description =
          """
                    Issues a new access token using a valid refresh token provided via an HttpOnly cookie.

                    Refresh workflow:
                    - The refresh token JWT is validated (signature and expiration).
                    - The token identifier (jti) is extracted from the JWT.
                    - The corresponding refresh token record is looked up in the database.
                    - The stored hash is verified and the token is revoked to prevent reuse.
                    - A new refresh token is generated, stored, and sent as a secure cookie.
                    - A new short-lived access token is returned in the response body.

                    Security guarantees:
                    - Refresh tokens are rotated on every successful refresh.
                    - Revoked or reused refresh tokens are rejected.
                    - Refresh tokens are never stored in plaintext.

                    Returns 401 if the refresh token is missing, invalid, expired, revoked, or reused.
                    """)
  @ApiResponse(responseCode = "200", description = "Access token refreshed successfully")
  @ApiResponse(responseCode = "401", description = "Invalid, expired, or revoked refresh token")
  @AuditSecurityEvent(SecurityEventType.TOKEN_REFRESH)
  @ApiRateLimit(capacity = 5, refillTokens = 5, refillDurationMinutes = 1, useIpAddress = true)
  @PostMapping("/refresh")
  public ResponseEntity<?> refresh(
      @CookieValue(name = "refresh_token", required = false) String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null) {
      return ResponseEntity.status(401).build();
    }

    return authService.refresh(refreshToken, response);
  }

  @AuditSecurityEvent(SecurityEventType.LOGOUT)
  @PostMapping("/logout")
  @Operation(
      summary = "Logout user and revoke refresh token",
      description =
          """
                    Logs out the current session by revoking the active refresh token and
                    deleting the refresh token cookie.

                    Logout behavior:
                    - If a refresh token cookie is present, its token identifier (jti) is extracted.
                    - The corresponding refresh token is revoked in the database.
                    - The refresh token cookie is deleted from the client.

                    Security notes:
                    - Revoking the refresh token ensures it cannot be reused, even if previously leaked.
                    - Cookie deletion alone is not relied upon for logout.

                    This operation logs out the current device/session only.
                    """)
  @ApiResponse(responseCode = "200", description = "Logged out successfully")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refresh_token", required = false) String refreshToken,
      HttpServletResponse response) {
    authService.logout(refreshToken, response);
    return ResponseEntity.ok().build();
  }

  @AuditSecurityEvent(SecurityEventType.LOGOUT)
  @PostMapping(value = "/refresh/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> logoutFromBrowser(
      @CookieValue(name = "refresh_token", required = false) String refreshToken,
      HttpServletResponse response) {
    authService.logout(refreshToken, response);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/check-username")
  @Operation(
      summary = "Check if username already exists",
      description = "Returns true if the username is already taken, false otherwise.")
  @ApiResponse(responseCode = "200", description = "Username existence checked successfully.")
  public ResponseEntity<Map<String, Boolean>> checkUsernameExists(@RequestParam String username) {
    boolean exists = userService.usernameExists(username);
    return ResponseEntity.ok(Map.of("exists", exists));
  }

  @GetMapping("/users")
  @Operation(
      summary = "Get all users",
      description =
          "Returns a list of all users with basic info (e.g., usernames) for displaying in the chat list.")
  @ApiResponse(responseCode = "200", description = "List of all users retrieved successfully.")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<List<UserResponseDto>> getAllUsers() {
    List<UserResponseDto> users =
        userService.getAllUsers().stream().map(UserResponseDto::new).toList();
    return ResponseEntity.ok(users);
  }

  @AuditSecurityEvent(SecurityEventType.PASSWORD_CHANGE)
  @ApiRateLimit(capacity = 5, refillTokens = 5, refillDurationMinutes = 1, useIpAddress = true)
  @PostMapping("/change-password")
  @Operation(
      summary = "Change password for the authenticated user",
      description =
          "User must provide the current password. The new password must meet the platform requirements.")
  @ApiResponse(responseCode = "204", description = "Password changed successfully.")
  @ApiResponse(
      responseCode = "401",
      description =
          "Unauthorized request. You must provide an authentication token along with the correct current password.")
  public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    userService.changePassword(currentUser, request.getCurrentPassword(), request.getNewPassword());
    return ResponseEntity.noContent().build();
  }

  @ApiRateLimit(capacity = 20, refillTokens = 20, refillDurationMinutes = 1, useIpAddress = true)
  @GetMapping("/security-activity")
  @Operation(
      summary = "Get security activity events for the authenticated user",
      description = "Returns a list of recent security-relevant events on the user's account.")
  @ApiResponse(
      responseCode = "200",
      description = "List of security events retrieved successfully.")
  @ApiResponse(responseCode = "401", description = "Unauthorized request.")
  public ResponseEntity<List<SecurityEventDto>> getSecurityActivity() {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    List<SecurityEventDto> events = userService.getSecurityEvents(currentUser);
    return ResponseEntity.ok(events);
  }

  @ApiRateLimit(capacity = 10, refillTokens = 10, refillDurationMinutes = 1, useIpAddress = true)
  @PostMapping("/security-activity/log")
  @Operation(
      summary = "Log a vault security event",
      description = "Exposes an endpoint to log client-side vault lock/unlock security events.")
  @ApiResponse(responseCode = "200", description = "Event logged successfully.")
  @ApiResponse(responseCode = "400", description = "Invalid event type.")
  @ApiResponse(responseCode = "401", description = "Unauthorized request.")
  public ResponseEntity<Void> logSecurityEvent(
      @RequestBody Map<String, String> payload, HttpServletRequest request) {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    String eventTypeStr = payload.get("eventType");
    if (eventTypeStr == null) {
      return ResponseEntity.badRequest().build();
    }
    SecurityEventType eventType;
    try {
      eventType = SecurityEventType.valueOf(eventTypeStr);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }

    if (eventType != SecurityEventType.VAULT_UNLOCKED
        && eventType != SecurityEventType.VAULT_LOCKED) {
      return ResponseEntity.badRequest().build();
    }

    userService.logSecurityEvent(currentUser, eventType, "SUCCESS", request, null);
    return ResponseEntity.ok().build();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Profile Picture Endpoints
  // ─────────────────────────────────────────────────────────────────────────

  /** Upload (or replace) the current user's profile picture. */
  @PostMapping(value = "/profile-picture", consumes = "multipart/form-data")
  @Operation(
      summary = "Upload or replace the current user's profile picture",
      description = "Accepts JPEG, PNG, or WebP. Maximum size is 2 MB.")
  @ApiResponse(responseCode = "200", description = "Profile picture uploaded successfully.")
  @ApiResponse(responseCode = "400", description = "Invalid file type or file too large.")
  @ApiResponse(responseCode = "401", description = "Unauthorized — must be logged in.")
  public ResponseEntity<Map<String, String>> uploadProfilePicture(
      @RequestParam("file") MultipartFile file) throws IOException {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }

    String oldPicturePath = currentUser.getProfilePicture();

    String newPicturePath = profilePictureService.store(file, currentUser.getId());
    try {
      userService.updateProfilePicture(currentUser, newPicturePath);
    } catch (RuntimeException ex) {
      profilePictureService.delete(newPicturePath);
      throw ex;
    }

    if (oldPicturePath != null && !oldPicturePath.isBlank()) {
      profilePictureService.delete(oldPicturePath);
    }

    return ResponseEntity.ok(Map.of("profilePicture", newPicturePath));
  }

  /** Get the current user's profile picture path. */
  @GetMapping("/profile-picture")
  @Operation(
      summary = "Get the current user's profile picture path",
      description = "Returns the relative path to the profile picture, or null if none is set.")
  @ApiResponse(responseCode = "200", description = "Profile picture path retrieved.")
  @ApiResponse(responseCode = "401", description = "Unauthorized — must be logged in.")
  public ResponseEntity<Map<String, String>> getProfilePicture() {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    return ResponseEntity.ok(
        java.util.Collections.singletonMap("profilePicture", currentUser.getProfilePicture()));
  }

  /** Delete the current user's profile picture. */
  @DeleteMapping("/profile-picture")
  @Operation(
      summary = "Delete the current user's profile picture",
      description = "Removes the picture from storage and clears it from the user profile.")
  @ApiResponse(responseCode = "204", description = "Profile picture deleted successfully.")
  @ApiResponse(responseCode = "401", description = "Unauthorized — must be logged in.")
  public ResponseEntity<Void> deleteProfilePicture() {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }

    profilePictureService.delete(currentUser.getProfilePicture());
    userService.removeProfilePicture(currentUser);
    return ResponseEntity.noContent().build();
  }
}
