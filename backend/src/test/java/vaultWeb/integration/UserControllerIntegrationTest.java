package vaultWeb.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import vaultWeb.dtos.user.ChangePasswordRequest;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.models.User;
import vaultWeb.security.JwtUtil;
import vaultWeb.support.TestJwtUtil;

class UserControllerIntegrationTest extends IntegrationTestBase {

  // ============================================================================
  // Test Constants
  // ============================================================================
  private static final String TEST_USERNAME = "testuser";
  private static final String TEST_PASSWORD = "TestPassword1!";
  private static final String NEW_PASSWORD = "NewPassword1!";
  private static final int REFRESH_TOKEN_MAX_AGE_SECONDS = 30 * 24 * 60 * 60; // 30 days
  private static final long EXPIRED_TOKEN_OFFSET_MS = -60 * 60 * 1000; // 1 hour in past

  // ============================================================================
  // Test Dependencies (mockMvc, objectMapper, userRepository inherited from base)
  // ============================================================================
  @Autowired private JwtUtil jwtUtil;
  @Autowired private TestJwtUtil testJwtUtil;

  /**
   * Creates a UserDto with the given username and password.
   *
   * @param username the username for the user
   * @param password the password for the user
   * @return a UserDto instance
   */
  private UserDto createUserDto(String username, String password) {
    UserDto dto = new UserDto();
    dto.setUsername(username);
    dto.setPassword(password);
    return dto;
  }

  /**
   * Extracts a cookie from the MvcResult response.
   *
   * @param result the MvcResult from a test request
   * @param cookieName the name of the cookie to extract
   * @return the Cookie object, or null if not found
   */
  private Cookie extractCookie(MvcResult result, String cookieName) {
    return result.getResponse().getCookie(cookieName);
  }

  /**
   * Extracts the JWT access token from a JSON response.
   *
   * @param result the MvcResult containing the JSON response
   * @return the access token string
   * @throws Exception if JSON parsing fails
   */
  private String extractTokenFromResponse(MvcResult result) throws Exception {
    String json = result.getResponse().getContentAsString();
    JsonNode node = objectMapper.readTree(json);
    JsonNode tokenNode = node.get("token");
    assertNotNull(tokenNode, "Response JSON does not contain 'token' field");
    return tokenNode.asText();
  }

  /**
   * Formats a JWT token into an Authorization header value.
   *
   * @param token the JWT access token
   * @return the formatted "Bearer {token}" string
   */
  private String authHeader(String token) {
    return "Bearer " + token;
  }

  /**
   * Performs a GET request to a protected endpoint using RestTemplate.
   *
   * <p>This helper method is used for testing filter-level authentication failures that occur
   * before Spring MVC's DispatcherServlet. RestTemplate makes real HTTP requests through the entire
   * servlet container stack, properly simulating how Spring Security filters handle invalid/expired
   * tokens and delegate to AuthenticationEntryPoint.
   *
   * @param endpoint the API endpoint path (e.g., "/api/auth/users")
   * @param authToken the Authorization header value, or null for no auth header
   * @return the ResponseEntity from the request
   */
  private ResponseEntity<String> performRestTemplateGet(String endpoint, String authToken) {
    HttpHeaders headers = new HttpHeaders();
    if (authToken != null) {
      headers.set("Authorization", authToken);
    }
    HttpEntity<String> entity = new HttpEntity<>(headers);

    return restTemplate.exchange(
        "http://localhost:" + port + endpoint, HttpMethod.GET, entity, String.class);
  }

  /**
   * Registers a new user via the /api/auth/register endpoint.
   *
   * @param testUser the user DTO containing registration details
   * @throws Exception if the registration request fails
   */
  private void registerUser(UserDto testUser) throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUser)))
        .andExpect(status().isOk())
        .andExpect(content().string("User registered successfully"));
  }

  /**
   * Logs in a user via the /api/auth/login endpoint and returns the MvcResult containing the access
   * token and refresh token cookie.
   *
   * @param testUser the user DTO containing login credentials
   * @return the MvcResult with access token in response body and refresh token in cookie
   * @throws Exception if the login request fails
   */
  private MvcResult loginUser(UserDto testUser) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").exists())
        .andReturn();
  }

  // ============================================================================
  // Input Validation (verifies @Valid wiring returns 400 for invalid input)
  // ============================================================================

  @Nested
  class InputValidation {

    @ParameterizedTest(name = "username: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRejectRegistration_WithInvalidUsername(String username) throws Exception {
      UserDto testUser = createUserDto(username, TEST_PASSWORD);

      mockMvc
          .perform(
              post("/api/auth/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "password: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"Short1!", "nouppercase1!", "NoDigit!", "NoSpecial1"})
    void shouldRejectRegistration_WithInvalidPassword(String password) throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, password);

      mockMvc
          .perform(
              post("/api/auth/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "username: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRejectLogin_WithInvalidUsername(String username) throws Exception {
      UserDto testUser = createUserDto(username, TEST_PASSWORD);

      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "password: \"{0}\"")
    @NullAndEmptySource
    void shouldRejectLogin_WithInvalidPassword(String password) throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, password);

      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isBadRequest());
    }
  }

  // ============================================================================
  // Registration
  // ============================================================================

  @Nested
  class Registration {

    @Test
    void shouldRegisterNewUser() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);

      registerUser(testUser);

      assertTrue(userRepository.findByUsername(testUser.getUsername()).isPresent());

      User savedUser = userRepository.findByUsername(testUser.getUsername()).get();
      assertTrue(savedUser.getPassword().startsWith("$2a$"));
    }

    @Test
    void shouldFailRegistration_WhenDuplicateUsername() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      mockMvc
          .perform(
              post("/api/auth/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
          .andExpect(jsonPath("$.message").value("Registration failed"));

      assertTrue(userRepository.findByUsername(testUser.getUsername()).isPresent());
    }
  }

  // ============================================================================
  // Login
  // ============================================================================

  @Nested
  class Login {

    @Test
    void shouldLogin_WithValidCredentials() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);

      MvcResult result = loginUser(testUser);

      Cookie refreshTokenCookie = extractCookie(result, "refresh_token");
      assertNotNull(refreshTokenCookie, "refresh_token cookie should be set");
      assertNotNull(refreshTokenCookie.getValue(), "refresh_token should have a value");
      assertTrue(refreshTokenCookie.isHttpOnly(), "refresh_token should be HttpOnly");
      assertTrue(refreshTokenCookie.getSecure(), "refresh_token should be Secure");
      assertEquals(
          "/api/auth/refresh",
          refreshTokenCookie.getPath(),
          "refresh_token path should be /api/auth/refresh");
      assertEquals(
          REFRESH_TOKEN_MAX_AGE_SECONDS,
          refreshTokenCookie.getMaxAge(),
          "refresh_token should expire in 30 days");

      String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
      assertNotNull(setCookieHeader, "Set-Cookie header should be present");
      assertTrue(
          setCookieHeader.contains("SameSite=None"),
          "refresh_token should have SameSite=None for cross-origin requests");
    }

    @Test
    void shouldFailLogin_WithWrongPassword() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);

      UserDto wrongPasswordUser = createUserDto(TEST_USERNAME, "WrongPassword1!");

      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(wrongPasswordUser)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
          .andExpect(jsonPath("$.message").value("Authentication failed"));
    }

    @Test
    void shouldFailLogin_WithNonExistentUser() throws Exception {
      UserDto nonExistentUser = createUserDto("nonexistent", TEST_PASSWORD);

      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(nonExistentUser)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
          .andExpect(jsonPath("$.message").value("Authentication failed"));
    }
  }

  // ============================================================================
  // JWT Token Validation
  // ============================================================================

  @Nested
  class JwtTokenValidation {

    @Test
    void shouldGenerateValidJwtToken_OnLogin() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String token = extractTokenFromResponse(result);
      assertTrue(jwtUtil.validateToken(token));
      String username = jwtUtil.extractUsername(token);
      assertEquals(testUser.getUsername(), username);
    }

    @Test
    void shouldAccessProtectedEndpoint_WithValidToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String token = extractTokenFromResponse(result);
      mockMvc
          .perform(get("/api/auth/users").header("Authorization", authHeader(token)))
          .andExpect(status().isOk())
          .andExpect(content().json("[{\"username\":\"" + TEST_USERNAME + "\"}]"));
    }
  }

  // ============================================================================
  // Filter-Level Authentication (RestTemplate Required)
  // ============================================================================
  // The following tests use RestTemplate instead of MockMvc because they test
  // authentication failures that occur in Spring Security filters, BEFORE reaching
  // Spring MVC's DispatcherServlet.
  //
  // When invalid/expired JWT tokens are processed:
  // 1. JwtAuthFilter throws JwtAuthenticationException
  // 2. Spring Security catches this and delegates to JwtAuthenticationEntryPoint
  // 3. The servlet container sends a 401 Unauthorized response
  //
  // MockMvc operates at the Spring MVC layer and may not fully simulate this
  // filter-level exception handling. RestTemplate makes real HTTP requests through
  // the entire servlet container stack, ensuring we test the actual behavior.
  // ============================================================================

  @Nested
  class FilterLevelAuthentication {

    @ParameterizedTest(name = "auth header: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"random_string", "Bearer ", "Bearer invalid_token"})
    void shouldRejectRequest_WithInvalidOrMissingAuth(String authHeaderValue) {
      ResponseEntity<String> response = performRestTemplateGet("/api/auth/users", authHeaderValue);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReject_WithExpiredToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);

      User savedUser = userRepository.findByUsername(testUser.getUsername()).get();

      String expiredToken =
          testJwtUtil.generateTokenWithExpiration(savedUser, EXPIRED_TOKEN_OFFSET_MS);

      ResponseEntity<String> response =
          performRestTemplateGet("/api/auth/users", authHeader(expiredToken));

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @ParameterizedTest(name = "prefix: \"{0}\"")
    @ValueSource(strings = {"", "bearer ", "BEARER "})
    void shouldReject_WithInvalidBearerPrefix(String prefix) throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      User savedUser = userRepository.findByUsername(testUser.getUsername()).get();
      String token = jwtUtil.generateToken(savedUser);

      ResponseEntity<String> response = performRestTemplateGet("/api/auth/users", prefix + token);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }

  // ============================================================================
  // Refresh Token Flow
  // ============================================================================

  @Nested
  class RefreshTokenFlow {

    @Test
    void shouldRefreshAccessToken_WithValidRefreshToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String refreshToken = extractCookie(result, "refresh_token").getValue();
      result =
          mockMvc
              .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.token").exists())
              .andReturn();

      Cookie newRefreshToken = extractCookie(result, "refresh_token");
      assertNotNull(newRefreshToken, "New refresh token should be set");
      assertNotNull(newRefreshToken.getValue(), "New refresh token should have a value");
      assertTrue(newRefreshToken.getValue().length() > 0, "New refresh token should have a value");
      assertNotEquals(newRefreshToken.getValue(), refreshToken, "Refresh token should be rotated");

      String oldTokenId = jwtUtil.extractTokenId(refreshToken);
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(oldTokenId).isEmpty(),
          "Old refresh token should be revoked after rotation");
    }

    @Test
    void shouldRotateRefreshToken_AndRejectOldToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String refreshToken = extractCookie(result, "refresh_token").getValue();

      // First refresh - should succeed and rotate the token
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.token").exists());

      // Second refresh with old token - should fail because token was rotated
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
          .andExpect(status().isUnauthorized());

      String oldTokenId = jwtUtil.extractTokenId(refreshToken);
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(oldTokenId).isEmpty(),
          "Old refresh token should be revoked after rotation");
    }

    @Test
    void shouldRejectRefresh_WithInvalidToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String refreshToken = extractCookie(result, "refresh_token").getValue();

      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "invalid_token")))
          .andExpect(status().isUnauthorized());

      String validTokenId = jwtUtil.extractTokenId(refreshToken);
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(validTokenId).isPresent(),
          "Valid refresh token should still be active after invalid token attempt");
    }

    @Test
    void shouldLogout_AndRevokeRefreshToken() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      Cookie browserCookie = extractCookie(result, "refresh_token");
      assertNotNull(browserCookie);
      String refreshToken = browserCookie.getValue();
      String logoutPath = browserCookie.getPath() + "/logout";
      mockMvc
          .perform(post(logoutPath).cookie(browserCookie).contentType(MediaType.TEXT_PLAIN))
          .andExpect(status().isUnsupportedMediaType());
      result =
          mockMvc
              .perform(
                  post(logoutPath)
                      .cookie(browserCookie)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andExpect(status().isOk())
              .andReturn();

      String tokenId = jwtUtil.extractTokenId(refreshToken);
      assertTrue(refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId).isEmpty());

      Cookie deletedCookie = extractCookie(result, "refresh_token");
      assertNotNull(deletedCookie, "refresh_token cookie should be deleted");
      assertEquals(0, deletedCookie.getMaxAge(), "refresh_token should be deleted");
      assertEquals(browserCookie.getPath(), deletedCookie.getPath());

      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
          .andExpect(status().isUnauthorized());
    }
  }

  // ============================================================================
  // Password Change
  // ============================================================================

  @Nested
  class PasswordChange {

    @Test
    void shouldChangePassword_WithValidCurrentPassword() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String token = extractTokenFromResponse(result);
      ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest();
      changePasswordRequest.setCurrentPassword(TEST_PASSWORD);
      changePasswordRequest.setNewPassword(NEW_PASSWORD);

      MvcResult changed =
          mockMvc
              .perform(
                  post("/api/auth/change-password")
                      .header("Authorization", authHeader(token))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(changePasswordRequest)))
              .andExpect(status().isOk())
              .andReturn();

      assertTrue(jwtUtil.validateToken(extractTokenFromResponse(changed)));
      Cookie previousSession = extractCookie(result, "refresh_token");
      Cookie replacement = extractCookie(changed, "refresh_token");
      assertNotNull(replacement);
      assertEquals(previousSession.getPath(), replacement.getPath());
      assertTrue(replacement.isHttpOnly());
      assertNotEquals(previousSession.getValue(), replacement.getValue());

      mockMvc
          .perform(post("/api/auth/refresh").cookie(previousSession))
          .andExpect(status().isUnauthorized());
      mockMvc.perform(post("/api/auth/refresh").cookie(replacement)).andExpect(status().isOk());

      testUser.setPassword(NEW_PASSWORD);
      result = loginUser(testUser);
      String newToken = extractTokenFromResponse(result);
      assertTrue(jwtUtil.validateToken(newToken), "New password should work for login");

      testUser.setPassword(TEST_PASSWORD);
      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(testUser)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectChangePassword_WithWrongCurrentPassword() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String token = extractTokenFromResponse(result);
      ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest();
      changePasswordRequest.setCurrentPassword("TestPassword2!");
      changePasswordRequest.setNewPassword(NEW_PASSWORD);
      mockMvc
          .perform(
              post("/api/auth/change-password")
                  .header("Authorization", authHeader(token))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(changePasswordRequest)))
          .andExpect(status().isUnauthorized());

      mockMvc
          .perform(post("/api/auth/refresh").cookie(extractCookie(result, "refresh_token")))
          .andExpect(status().isOk());
    }
  }

  // ============================================================================
  // Full E2E Scenarios
  // ============================================================================

  @Nested
  class EndToEnd {

    @Test
    void shouldCompleteFullAuthenticationFlow() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);
      MvcResult result = loginUser(testUser);
      String token = extractTokenFromResponse(result);
      String refreshToken = extractCookie(result, "refresh_token").getValue();

      // Access protected endpoint with valid token
      mockMvc
          .perform(get("/api/auth/users").header("Authorization", authHeader(token)))
          .andExpect(status().isOk())
          .andExpect(content().json("[{\"username\":\"" + TEST_USERNAME + "\"}]"));

      // Refresh token to get new tokens (this rotates the refresh token)
      result =
          mockMvc
              .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.token").exists())
              .andReturn();
      String newAccessToken = extractTokenFromResponse(result);
      String newRefreshToken = extractCookie(result, "refresh_token").getValue();
      assertTrue(jwtUtil.validateToken(newAccessToken), "New access token should be valid");

      // Logout with the NEW refresh token
      mockMvc
          .perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", newRefreshToken)))
          .andExpect(status().isOk());

      // Verify the NEW refresh token is revoked after logout
      String newTokenId = jwtUtil.extractTokenId(newRefreshToken);
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(newTokenId).isEmpty(),
          "New refresh token should be revoked after logout");

      // Verify refresh with revoked token fails
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", newRefreshToken)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldHandleMultipleSessions_PerUser() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);

      // Login from "client 1" to get first refresh token
      MvcResult result1 = loginUser(testUser);
      String refreshToken1 = extractCookie(result1, "refresh_token").getValue();
      String tokenId1 = jwtUtil.extractTokenId(refreshToken1);

      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId1).isPresent(),
          "First refresh token should exist and not be revoked");

      // Login from "client 2" to get second refresh token (simulates login from another device)
      MvcResult result2 = loginUser(testUser);
      String refreshToken2 = extractCookie(result2, "refresh_token").getValue();
      String tokenId2 = jwtUtil.extractTokenId(refreshToken2);

      // Verify single session enforcement
      User user = userRepository.findByUsername(TEST_USERNAME).orElseThrow();
      long nonRevokedCount =
          refreshTokenRepository.findAll().stream()
              .filter(token -> !token.isRevoked() && token.getUser().getId().equals(user.getId()))
              .count();
      assertEquals(1, nonRevokedCount, "Only one non-revoked refresh token should exist per user");

      // Verify first refresh token is revoked (second login should revoke first token)
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId1).isEmpty(),
          "First refresh token should be revoked after second login");

      // Verify second refresh token is active
      assertTrue(
          refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId2).isPresent(),
          "Second refresh token should be active");

      // Verify first token no longer works (returns 401)
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken1)))
          .andExpect(status().isUnauthorized());

      // Verify second token works correctly
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken2)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.token").exists());
    }
  }

  // ============================================================================
  // Username Check
  // ============================================================================

  @Nested
  class UsernameCheck {

    @Test
    void shouldCheckUsername_Availability() throws Exception {
      UserDto testUser = createUserDto(TEST_USERNAME, TEST_PASSWORD);
      registerUser(testUser);

      mockMvc
          .perform(get("/api/auth/check-username").param("username", "testuser"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.exists").value(true));

      mockMvc
          .perform(get("/api/auth/check-username").param("username", "nonexistent"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void shouldRejectCheckUsername_WithMissingParam() throws Exception {
      mockMvc.perform(get("/api/auth/check-username")).andExpect(status().isBadRequest());
    }
  }
}
