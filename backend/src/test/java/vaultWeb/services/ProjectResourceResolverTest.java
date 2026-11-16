package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vaultWeb.models.ProjectResourceLink;
import vaultWeb.models.User;

class ProjectResourceResolverTest {
  private CloudPageMediaClient cloud;
  private ProjectResourceResolver resolver;
  private ProjectResourceLink link;
  private User owner, caller;

  @BeforeEach
  void setup() {
    cloud = mock(CloudPageMediaClient.class);
    resolver = new ProjectResourceResolver(cloud, new ObjectMapper());
    owner = new User();
    owner.setId(1L);
    owner.setUsername("owner");
    caller = new User();
    caller.setId(2L);
    caller.setUsername("reader");
    link = new ProjectResourceLink();
    link.setId(3L);
    link.setOwner(owner);
    link.setPath("/note.md");
  }

  private HttpResponse<InputStream> response(int status, String json) {
    return new HttpResponse<>() {
      public int statusCode() {
        return status;
      }

      public InputStream body() {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
      }

      public java.net.http.HttpRequest request() {
        return null;
      }

      public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
        return java.util.Optional.empty();
      }

      public java.net.http.HttpHeaders headers() {
        return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
      }

      public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
        return java.util.Optional.empty();
      }

      public java.net.URI uri() {
        return java.net.URI.create("http://synthetic.invalid");
      }

      public java.net.http.HttpClient.Version version() {
        return java.net.http.HttpClient.Version.HTTP_1_1;
      }
    };
  }

  private String share(String ownerName, String path, boolean revoked, String permission) {
    return "[{\"id\":\"abc-123\",\"ownerUsername\":\""
        + ownerName
        + "\",\"recipientUsername\":\"reader\","
        + "\"relativePath\":\""
        + path
        + "\",\"resourceType\":\"FILE\",\"revoked\":"
        + revoked
        + ",\"permissions\":[\""
        + permission
        + "\"]}]";
  }

  @Test
  void independentExactShareUsesOnlyCallerToken() throws Exception {
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenReturn(response(200, share("owner", "note.md", false, "VIEW")));
    when(cloud.fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token"))
        .thenReturn(response(206, "x"));
    var result = resolver.resolve(link, caller, "Bearer reader-token");
    assertTrue(result.available());
    assertEquals("abc-123", result.shareId());
    verify(cloud).fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token");
    verify(cloud).fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token");
    verifyNoMoreInteractions(cloud);
  }

  @Test
  void unauthorizedRevokedWrongOwnerAndMovedReferencesRevealNothing() throws Exception {
    for (String json :
        new String[] {
          "[]",
          share("other", "note.md", false, "VIEW"),
          share("owner", "different.md", false, "VIEW"),
          share("owner", "note.md", true, "VIEW"),
          share("owner", "note.md", false, "DOWNLOAD")
        }) {
      when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
          .thenReturn(response(200, json));
      var result = resolver.resolve(link, caller, "Bearer reader-token");
      assertFalse(result.available());
      assertNull(result.path());
      assertNull(result.ownerUsername());
      assertNull(result.shareId());
    }
    assertFalse(resolver.resolve(link, caller, null).available());
  }

  @Test
  void liveAccessRevocationAndOfflineCloudFailClosed() throws Exception {
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenReturn(response(200, share("owner", "note.md", false, "VIEW")));
    when(cloud.fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token"))
        .thenReturn(response(403, ""));
    assertFalse(resolver.resolve(link, caller, "Bearer reader-token").available());
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenThrow(new java.io.IOException());
    assertFalse(resolver.resolve(link, caller, "Bearer reader-token").available());
  }

  @Test
  void ownerChecksExistingResourceAndCanonicalPathsRejectTraversal() throws Exception {
    when(cloud.fetchProjectReference("/api/files/view?path=%2Fnote.md", "Bearer owner-token"))
        .thenReturn(response(404, ""));
    assertFalse(resolver.resolve(link, owner, "Bearer owner-token").available());
    for (String path :
        new String[] {"../private", "/.trash/1", "a\\b", "a//b", "a/./b", "a\u0000b"})
      assertThrows(IllegalArgumentException.class, () -> ProjectResourceResolver.canonical(path));
    assertEquals("/a/b", ProjectResourceResolver.canonical("a/b/"));
  }
}
