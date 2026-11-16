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
  void setup() throws Exception {
    cloud = mock(CloudPageMediaClient.class);
    when(cloud.fetchProjectReference(anyString(), anyString(), any()))
        .thenAnswer(
            inv ->
                cloud.fetchProjectReference(
                    (String) inv.getArgument(0), (String) inv.getArgument(1)));
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
    return response(status, json, java.util.Map.of());
  }

  private HttpResponse<InputStream> response(
      int status, String json, java.util.Map<String, java.util.List<String>> headers) {
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
        return java.net.http.HttpHeaders.of(headers, (a, b) -> true);
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
  void authenticatedEmptyFilesRemainAccessibleForOwnerAndExactShare() throws Exception {
    var empty =
        response(416, "", java.util.Map.of("Content-Range", java.util.List.of("bytes */0")));
    when(cloud.fetchProjectReference("/api/files/view?path=%2Fnote.md", "Bearer owner-token"))
        .thenReturn(empty);
    assertTrue(resolver.resolve(link, owner, "Bearer owner-token").available());
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenReturn(response(200, share("owner", "note.md", false, "VIEW")));
    when(cloud.fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token"))
        .thenReturn(response(206, "x"), empty);
    assertTrue(resolver.resolve(link, caller, "Bearer reader-token").available());
    assertTrue(resolver.resolve(link, caller, "Bearer reader-token").available());
  }

  @Test
  void batchFetchesSharesOnceAndChecksEachResourceForLiveRevocation() throws Exception {
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenReturn(response(200, share("owner", "note.md", false, "VIEW")));
    when(cloud.fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token"))
        .thenReturn(response(206, "x"), response(403, ""));
    var result = resolver.resolveAll(java.util.List.of(link, link), caller, "Bearer reader-token");
    assertTrue(result.get(0).available());
    assertFalse(result.get(1).available());
    assertNull(result.get(1).path());
    verify(cloud).fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token");
    verify(cloud, times(2))
        .fetchProjectReference("/api/shares/abc-123/view", "Bearer reader-token");
  }

  @Test
  void failedShareListingIsNotRetriedForEveryReference() throws Exception {
    when(cloud.fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token"))
        .thenThrow(new java.io.IOException());
    var result =
        resolver.resolveAll(java.util.Collections.nCopies(50, link), caller, "Bearer reader-token");
    assertTrue(result.stream().noneMatch(r -> r.available()));
    verify(cloud).fetchProjectReference("/api/shares/shared-with-me", "Bearer reader-token");
  }

  @Test
  void otherRangeErrorsAndUnauthorizedEmptyResponsesRemainPrivate() throws Exception {
    for (var rejected :
        java.util.List.of(
            response(416, ""),
            response(416, "", java.util.Map.of("Content-Range", java.util.List.of("bytes */10"))),
            response(403, "", java.util.Map.of("Content-Range", java.util.List.of("bytes */0"))),
            response(404, "", java.util.Map.of("Content-Range", java.util.List.of("bytes */0"))))) {
      when(cloud.fetchProjectReference("/api/files/view?path=%2Fnote.md", "Bearer owner-token"))
          .thenReturn(rejected);
      var result = resolver.resolve(link, owner, "Bearer owner-token");
      assertFalse(result.available());
      assertNull(result.path());
    }
    link.setFolder(true);
    when(cloud.fetchProjectReference("/api/folders/path?path=%2Fnote.md", "Bearer owner-token"))
        .thenReturn(
            response(416, "", java.util.Map.of("Content-Range", java.util.List.of("bytes */0"))));
    assertFalse(resolver.resolve(link, owner, "Bearer owner-token").available());
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
    verify(cloud, times(2)).fetchProjectReference(anyString(), eq("Bearer reader-token"), any());
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
