package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vaultWeb.security.MediaTokenService;
import vaultWeb.services.CloudPageMediaClient;

/**
 * MediaController is deliberately reachable at /api/media/stream/** without an Authorization header
 * (native <video>/<audio>/<img> elements cannot send one), so these tests focus on the things that
 * stand in for that missing header: the token itself must be the sole source of truth for who the
 * request acts as and which file it may read.
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

  @Mock private MediaTokenService mediaTokenService;
  @Mock private CloudPageMediaClient cloudPageMediaClient;

  @InjectMocks private MediaController mediaController;

  private Authentication authFor(String username) {
    return new UsernamePasswordAuthenticationToken(username, null, List.of());
  }

  private Claims claimsFor(String username) {
    Claims claims = mock(Claims.class);
    when(claims.getSubject()).thenReturn(username);
    return claims;
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> mockUpstreamResponse(
      int statusCode, String body, Map<String, String> headers) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

    HttpHeaders httpHeaders =
        HttpHeaders.of(
            headers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
            (a, b) -> true);
    when(response.headers()).thenReturn(httpHeaders);
    return response;
  }

  /** Runs a StreamingResponseBody to get the bytes it actually writes, the way Tomcat would. */
  private String writeBody(StreamingResponseBody body) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  // ============================================================================
  // POST /api/media/token
  // ============================================================================

  @Test
  void shouldCreateMediaTokenSuccessfully() {
    Authentication authentication = authFor("Ted");
    when(mediaTokenService.generateToken("Ted", "/videos/clip.mp4")).thenReturn("signed-token");
    when(mediaTokenService.getTtlSeconds()).thenReturn(300L);

    ResponseEntity<Map<String, Object>> response =
        mediaController.createMediaToken(
            new MediaController.MediaTokenRequest("/videos/clip.mp4"), authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("signed-token", response.getBody().get("token"));
    assertEquals(300L, response.getBody().get("expiresIn"));
    verify(mediaTokenService, times(1)).generateToken("Ted", "/videos/clip.mp4");
  }

  @Test
  void shouldRejectMediaToken_WhenPathIsBlank() {
    Authentication authentication = authFor("Ted");

    ResponseEntity<Map<String, Object>> response =
        mediaController.createMediaToken(new MediaController.MediaTokenRequest(""), authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoInteractions(mediaTokenService);
  }

  @Test
  void shouldRejectMediaToken_WhenPathIsNull() {
    Authentication authentication = authFor("Ted");

    ResponseEntity<Map<String, Object>> response =
        mediaController.createMediaToken(
            new MediaController.MediaTokenRequest(null), authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoInteractions(mediaTokenService);
  }

  @Test
  void shouldRejectMediaToken_WhenRequestBodyIsNull() {
    Authentication authentication = authFor("Ted");

    ResponseEntity<Map<String, Object>> response =
        mediaController.createMediaToken(null, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoInteractions(mediaTokenService);
  }

  /**
   * The username must come from the authenticated principal, never from anything the client
   * supplies. Otherwise a user could mint a token that reads someone else's files just by naming
   * them in the request.
   */
  @Test
  void shouldUseAuthenticatedUsername_NotAnyClientSuppliedValue() {
    Authentication authentication = authFor("Alice");
    when(mediaTokenService.generateToken(anyString(), anyString())).thenReturn("signed-token");
    when(mediaTokenService.getTtlSeconds()).thenReturn(300L);

    mediaController.createMediaToken(
        new MediaController.MediaTokenRequest("/videos/clip.mp4"), authentication);

    verify(mediaTokenService, times(1)).generateToken("Alice", "/videos/clip.mp4");
  }

  // ============================================================================
  // GET /api/media/stream/{token}
  // ============================================================================

  @Test
  void shouldStreamMedia_WhenTokenIsValid() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    HttpResponse<InputStream> upstream =
        mockUpstreamResponse(200, "video-bytes", Map.of("Content-Type", "video/mp4"));
    when(cloudPageMediaClient.fetch("Ted", "/videos/clip.mp4", null, null, null))
        .thenReturn(upstream);

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("video/mp4", response.getHeaders().getFirst("Content-Type"));
    assertEquals("video-bytes", writeBody(response.getBody()));
  }

  @Test
  void shouldForwardRangeHeader_ToCloudPage() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    HttpResponse<InputStream> upstream =
        mockUpstreamResponse(
            206, "234", Map.of("Content-Type", "video/mp4", "Content-Range", "bytes 2-4/10"));
    when(cloudPageMediaClient.fetch("Ted", "/videos/clip.mp4", "bytes=2-4", null, null))
        .thenReturn(upstream);

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", "bytes=2-4", null, null);

    assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
    assertEquals("bytes 2-4/10", response.getHeaders().getFirst("Content-Range"));
    assertEquals("234", writeBody(response.getBody()));
    verify(cloudPageMediaClient, times(1))
        .fetch("Ted", "/videos/clip.mp4", "bytes=2-4", null, null);
  }

  @Test
  void shouldRejectStream_WhenTokenIsExpiredOrInvalid() throws Exception {
    when(mediaTokenService.parseToken("bad-token")).thenThrow(new JwtException("expired"));

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("bad-token", null, null, null);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    verifyNoInteractions(cloudPageMediaClient);
  }

  @Test
  void shouldReturnNotModified_WhenUpstreamReturns304() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    HttpResponse<InputStream> upstream = mockUpstreamResponse(304, "", Map.of());
    when(cloudPageMediaClient.fetch("Ted", "/videos/clip.mp4", null, "\"etag\"", null))
        .thenReturn(upstream);

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", null, "\"etag\"", null);

    assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
    assertNull(response.getBody());
  }

  @Test
  void shouldReturnBadGateway_WhenCloudPageIsUnreachable() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    when(cloudPageMediaClient.fetch(anyString(), anyString(), any(), any(), any()))
        .thenThrow(new IOException("connection refused"));

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", null, null, null);

    assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
  }

  @Test
  void shouldReturnServiceUnavailable_WhenForwardingIsInterrupted() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    when(cloudPageMediaClient.fetch(anyString(), anyString(), any(), any(), any()))
        .thenThrow(new InterruptedException());

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", null, null, null);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    // The interrupt flag must be restored on the current thread, not swallowed.
    assertEquals(true, Thread.interrupted());
  }

  /**
   * The file path a token grants access to must come only from the token's own claim. There is no
   * path parameter on this endpoint's signature at all — this test documents that invariant so a
   * future change doesn't accidentally introduce one.
   */
  @Test
  void shouldResolvePathOnlyFromToken_NeverFromAnyOtherSource() throws Exception {
    Claims claims = claimsFor("Ted");
    when(mediaTokenService.parseToken("good-token")).thenReturn(claims);
    when(mediaTokenService.extractPath(claims)).thenReturn("/videos/clip.mp4");
    HttpResponse<InputStream> upstream = mockUpstreamResponse(200, "x", Map.of());
    when(cloudPageMediaClient.fetch(eq("Ted"), eq("/videos/clip.mp4"), any(), any(), any()))
        .thenReturn(upstream);

    ResponseEntity<StreamingResponseBody> response =
        mediaController.stream("good-token", null, null, null);
    writeBody(response.getBody());

    verify(cloudPageMediaClient, times(1))
        .fetch(eq("Ted"), eq("/videos/clip.mp4"), any(), any(), any());
    verify(cloudPageMediaClient, never())
        .fetch(anyString(), eq("/etc/passwd"), any(), any(), any());
  }
}
