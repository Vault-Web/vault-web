package vaultWeb.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vaultWeb.dtos.user.ChangePasswordRequest;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.models.PersonalAccessToken;
import vaultWeb.security.TokenHashUtil;
import vaultWeb.security.annotations.SecurityEventType;

class PersonalAccessTokenIntegrationTest extends IntegrationTestBase {

  private static final String PASSWORD = "TestPassword1!";
  private static final String TOKEN_REGEX = "^vw_pat_[0-9A-Za-z]{12}_[A-Za-z0-9_-]{43}$";

  private String aliceJwt;

  @BeforeEach
  void createUser() throws Exception {
    aliceJwt = registerAndLogin("alice");
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private String registerAndLogin(String username) throws Exception {
    UserDto dto = new UserDto();
    dto.setUsername(username);
    dto.setPassword(PASSWORD);
    String body = objectMapper.writeValueAsString(dto);
    mockMvc
        .perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    MvcResult login =
        mockMvc
            .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
  }

  private String createBody(List<String> scopes, int days, String password) throws Exception {
    return objectMapper.writeValueAsString(
        Map.of(
            "name", "backup-client",
            "scopes", scopes,
            "expiresInDays", days,
            "currentPassword", password));
  }

  private JsonNode createToken(String jwt, List<String> scopes) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/tokens")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(scopes, 30, PASSWORD)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String createRawToken(List<String> scopes) throws Exception {
    return createToken(aliceJwt, scopes).get("token").asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private PersonalAccessToken storedToken(String raw) {
    String publicId = raw.substring("vw_pat_".length(), "vw_pat_".length() + 12);
    return personalAccessTokenRepository.findByPublicId(publicId).orElseThrow();
  }

  private boolean hasEvent(SecurityEventType type, String status) {
    return securityEventRepository.findAll().stream()
        .anyMatch(e -> e.getEventType() == type && status.equals(e.getStatus()));
  }

  // ---------------------------------------------------------------------------
  // lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  class Lifecycle {

    @Test
    void create_returnsSecretOnce_andPersistsOnlyDigest() throws Exception {
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/auth/tokens")
                      .header("Authorization", bearer(aliceJwt))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(createBody(List.of("profile:read"), 30, PASSWORD)))
              .andExpect(status().isCreated())
              .andExpect(header().string("Cache-Control", "no-store"))
              .andExpect(jsonPath("$.metadata.status").value("ACTIVE"))
              .andExpect(jsonPath("$.metadata.scopes[0]").value("profile:read"))
              .andExpect(jsonPath("$.metadata.audience").value("core"))
              .andReturn();

      String raw =
          objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
      assertTrue(raw.matches(TOKEN_REGEX), "unexpected token format");

      PersonalAccessToken stored = storedToken(raw);
      assertEquals(TokenHashUtil.sha256(raw), stored.getTokenHash());
      assertNotEquals(raw, stored.getTokenHash());
      assertNotNull(stored.getExpiresAt());

      String listing =
          mockMvc
              .perform(get("/api/auth/tokens").header("Authorization", bearer(aliceJwt)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$[0].publicId").value(stored.getPublicId()))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(listing.contains(raw), "listing must not expose the secret");
      assertFalse(listing.contains(stored.getTokenHash()), "listing must not expose the digest");
      assertFalse(listing.contains("tokenHash"));

      assertTrue(hasEvent(SecurityEventType.PAT_CREATED, "SUCCESS"));
    }

    @Test
    void create_requiresCorrectCurrentPassword() throws Exception {
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of("profile:read"), 30, "WrongPassword1!")))
          .andExpect(status().isUnauthorized());

      assertEquals(0, personalAccessTokenRepository.count());
      assertTrue(hasEvent(SecurityEventType.PAT_CREATED, "FAILURE"));
    }

    @Test
    void create_requiresAuthentication() throws Exception {
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of("profile:read"), 30, PASSWORD)))
          .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloud:read", "cloud:upload", "cloud:write", "admin", "PROFILE_READ"})
    void create_rejectsUnsupportedScopes(String scope) throws Exception {
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of("profile:read", scope), 30, PASSWORD)))
          .andExpect(status().isBadRequest());
      assertEquals(0, personalAccessTokenRepository.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 366, 10_000})
    void create_rejectsLifetimeOutsideBounds(int days) throws Exception {
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of("profile:read"), days, PASSWORD)))
          .andExpect(status().isBadRequest());
    }

    @Test
    void create_rejectsMissingScopes() throws Exception {
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of(), 30, PASSWORD)))
          .andExpect(status().isBadRequest());
    }

    @Test
    void create_enforcesActiveTokenLimit() throws Exception {
      for (int i = 0; i < 20; i++) {
        createToken(aliceJwt, List.of("profile:read"));
      }
      mockMvc
          .perform(
              post("/api/auth/tokens")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(List.of("profile:read"), 30, PASSWORD)))
          .andExpect(status().isBadRequest());
    }

    @Test
    void scopes_listsOnlySupportedScopes() throws Exception {
      mockMvc
          .perform(get("/api/auth/tokens/scopes").header("Authorization", bearer(aliceJwt)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].value").value("profile:read"))
          .andExpect(jsonPath("$[1].value").value("security:read"));
    }

    @Test
    void list_onlyShowsOwnTokens() throws Exception {
      createRawToken(List.of("profile:read"));
      String bobJwt = registerAndLogin("bob");

      mockMvc
          .perform(get("/api/auth/tokens").header("Authorization", bearer(bobJwt)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revoke_otherUsersToken_returns404_andTokenKeepsWorking() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      String publicId = storedToken(raw).getPublicId();
      String bobJwt = registerAndLogin("bob");

      mockMvc
          .perform(delete("/api/auth/tokens/" + publicId).header("Authorization", bearer(bobJwt)))
          .andExpect(status().isNotFound());

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());
    }

    @Test
    void revoke_isIdempotent_andAudited() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      String publicId = storedToken(raw).getPublicId();

      for (int i = 0; i < 2; i++) {
        mockMvc
            .perform(
                delete("/api/auth/tokens/" + publicId).header("Authorization", bearer(aliceJwt)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVOKED"));
      }
      assertTrue(hasEvent(SecurityEventType.PAT_REVOKED, "SUCCESS"));
    }
  }

  // ---------------------------------------------------------------------------
  // authentication & authorization with a PAT
  // ---------------------------------------------------------------------------

  @Nested
  class Enforcement {

    @Test
    void allowedEndpoint_withScope_succeeds_andResolvesOwner() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.profile.username").value("alice"));
    }

    @Test
    void allowedEndpoint_withoutScope_isForbidden() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      mockMvc
          .perform(get("/api/auth/security-activity").header("Authorization", bearer(raw)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void securityRead_scope_allowsSecurityActivity() throws Exception {
      String raw = createRawToken(List.of("security:read"));
      mockMvc
          .perform(get("/api/auth/security-activity").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isForbidden());
    }

    /**
     * Token lifecycle, session/refresh, admin-like, chat, group, E2EE device-key, poll and media
     * routes must all be denied for PAT callers, even when the token holds every scope.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
      "GET, /api/auth/tokens",
      "POST, /api/auth/tokens",
      "GET, /api/auth/tokens/scopes",
      "DELETE, /api/auth/tokens/abc",
      "POST, /api/auth/refresh",
      "POST, /api/auth/logout",
      "POST, /api/auth/login",
      "POST, /api/auth/change-password",
      "GET, /api/auth/users",
      "POST, /api/auth/profile-picture",
      "DELETE, /api/auth/profile-picture",
      "POST, /api/auth/security-activity/log",
      "GET, /api/dashboard/alice",
      "GET, /api/dashboard/me/",
      "POST, /api/devices/register",
      "GET, /api/private-chats/user-chats",
      "GET, /api/private-chats/devices",
      "GET, /api/groups/my-groups",
      "GET, /api/groups/1/devices",
      "GET, /api/groups/1/messages",
      "POST, /api/media/token",
      "GET, /api/projects",
      "GET, /api/projects/1",
      "GET, /ws-chat/info",
      "GET, /v3/api-docs"
    })
    void unsupportedEndpoints_areDenied(String method, String path) throws Exception {
      String raw = createRawToken(List.of("profile:read", "security:read"));
      mockMvc
          .perform(request(HttpMethod.valueOf(method), path).header("Authorization", bearer(raw)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
      assertEquals(1, personalAccessTokenRepository.count(), "PAT must not mint more PATs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "WELLFORMED", "WELLFORMED_TOO_LONG", "BAD_SEPARATOR"})
    void malformedOrUnknownTokens_areUnauthorized(String variant) throws Exception {
      String wellFormed = "vw_pat_" + "A".repeat(12) + "_" + "A".repeat(43);
      String token =
          switch (variant) {
            case "WELLFORMED" -> wellFormed; // correct shape, unknown public id
            case "WELLFORMED_TOO_LONG" -> wellFormed + "A";
            case "BAD_SEPARATOR" -> "vw_pat_" + "A".repeat(12) + "-" + "A".repeat(43);
            default -> "vw_pat_" + variant;
          };
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(token)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tamperedSecret_isUnauthorized() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      char last = raw.charAt(raw.length() - 1);
      String tampered = raw.substring(0, raw.length() - 1) + (last == 'A' ? 'B' : 'A');
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(tampered)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedToken_isRejectedOnNextRequest() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());

      mockMvc
          .perform(
              delete("/api/auth/tokens/" + storedToken(raw).getPublicId())
                  .header("Authorization", bearer(aliceJwt)))
          .andExpect(status().isOk());

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_isUnauthorized() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      PersonalAccessToken stored = storedToken(raw);
      stored.setExpiresAt(Instant.now().minusSeconds(1));
      personalAccessTokenRepository.save(stored);

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChange_revokesAllTokens() throws Exception {
      String raw = createRawToken(List.of("profile:read"));

      ChangePasswordRequest change = new ChangePasswordRequest();
      change.setCurrentPassword(PASSWORD);
      change.setNewPassword("NewPassword1!");
      mockMvc
          .perform(
              post("/api/auth/change-password")
                  .header("Authorization", bearer(aliceJwt))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(change)))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isUnauthorized());
      assertNotNull(storedToken(raw).getRevokedAt());
      assertTrue(hasEvent(SecurityEventType.PAT_REVOKED_ALL, "SUCCESS"));
    }

    @Test
    void lastUsedAt_isUpdatedAtMostOncePerWindow() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      assertNull(storedToken(raw).getLastUsedAt());

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());
      Instant first = storedToken(raw).getLastUsedAt();
      assertNotNull(first);

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());
      assertEquals(first, storedToken(raw).getLastUsedAt(), "must not rewrite within the window");

      PersonalAccessToken stored = storedToken(raw);
      Instant old = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
      stored.setLastUsedAt(old);
      personalAccessTokenRepository.save(stored);

      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isOk());
      assertTrue(storedToken(raw).getLastUsedAt().isAfter(old));
    }

    @Test
    void requests_areThrottledPerToken() throws Exception {
      String raw = createRawToken(List.of("profile:read"));
      // app.pat.rate-limit-per-minute=10 in the test profile
      for (int i = 0; i < 10; i++) {
        mockMvc
            .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
            .andExpect(status().isOk());
      }
      mockMvc
          .perform(get("/api/dashboard/me").header("Authorization", bearer(raw)))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().exists("Retry-After"));
    }

    @Test
    void browserJwt_isUnaffected() throws Exception {
      createRawToken(List.of("profile:read"));
      mockMvc
          .perform(get("/api/groups/my-groups").header("Authorization", bearer(aliceJwt)))
          .andExpect(status().isOk());
      mockMvc
          .perform(get("/api/auth/security-activity").header("Authorization", bearer(aliceJwt)))
          .andExpect(status().isOk());
    }
  }
}
