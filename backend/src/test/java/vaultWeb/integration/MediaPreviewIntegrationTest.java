package vaultWeb.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class MediaPreviewIntegrationTest extends IntegrationTestBase {
  @Test
  void renewalRequiresBearerAuthenticationAndAnUnrevokedBrowserSession() throws Exception {
    String credentials =
        objectMapper.writeValueAsString(
            Map.of("username", "previewuser", "password", "PreviewPassword1!"));
    mockMvc
        .perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
        .andExpect(status().isOk());
    var login =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var refresh = login.getCookie("refresh_token");
    assertNotNull(refresh);
    String authorization =
        "Bearer " + objectMapper.readTree(login.getContentAsString()).path("token").asText();
    var preview =
        mockMvc
            .perform(
                post("/api/media/token")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"path\":\"movie.mp4\",\"renewable\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String id = objectMapper.readTree(preview.getContentAsString()).path("previewId").asText();
    String request = objectMapper.writeValueAsString(Map.of("id", id));

    mockMvc
        .perform(
            post("/api/auth/refresh/media")
                .cookie(refresh)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/auth/refresh/media")
                .cookie(refresh)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isNoContent());

    refreshTokenRepository.revokeAllByUser(
        userRepository.findByUsername("previewuser").orElseThrow().getId());
    mockMvc
        .perform(
            post("/api/auth/refresh/media")
                .cookie(refresh)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isForbidden());
  }
}
