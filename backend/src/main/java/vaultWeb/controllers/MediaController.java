package vaultWeb.controllers;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vaultWeb.security.MediaTokenService;
import vaultWeb.services.CloudPageMediaClient;

/**
 * Serves file bytes to native browser media elements.
 *
 * <p>An {@code <img>}, {@code <video>} or {@code <audio>} tag issues its own requests and cannot
 * carry an {@code Authorization} header, so the previous approach was to download a whole file over
 * XHR and hand the element a blob URL. That defeats streaming entirely: playback cannot begin until
 * the last byte arrives, seeking is impossible, and the file is held in memory.
 *
 * <p>This controller replaces that with a two-step flow:
 *
 * <ol>
 *   <li>the client calls {@code POST /api/media/token} over its normal authenticated channel and
 *       receives a short-lived token scoped to one file;
 *   <li>the client points the media element at {@code GET /api/media/stream/{token}}, which streams
 *       the bytes and honours {@code Range} requests, so the browser can seek.
 * </ol>
 *
 * <p>The streaming endpoint is reachable without the standard authentication filter — a media
 * element could not satisfy it — and is instead authenticated by the token in its path. That token
 * is signed with a dedicated key and pinned to a single path, so it grants nothing beyond the one
 * file it names.
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

  private static final Logger log = LoggerFactory.getLogger(MediaController.class);

  /**
   * Response headers copied from cloud-page. These carry the type, cacheability and range
   * bookkeeping the browser needs; hop-by-hop and length headers are deliberately excluded, since
   * the servlet container recalculates those for the response it actually writes.
   */
  private static final List<String> FORWARDED_RESPONSE_HEADERS =
      List.of(
          HttpHeaders.CONTENT_TYPE,
          HttpHeaders.CONTENT_DISPOSITION,
          HttpHeaders.CONTENT_RANGE,
          HttpHeaders.ACCEPT_RANGES,
          HttpHeaders.ETAG,
          HttpHeaders.LAST_MODIFIED,
          HttpHeaders.CACHE_CONTROL);

  private final MediaTokenService mediaTokenService;

  private final CloudPageMediaClient cloudPageMediaClient;

  /**
   * Issues a media token for one file.
   *
   * <p>This runs behind normal authentication, so the username is taken from the security context
   * rather than from anything the caller supplied. A user can therefore only ever mint tokens
   * against their own storage.
   */
  @PostMapping("/token")
  public ResponseEntity<Map<String, Object>> createMediaToken(
      @RequestBody MediaTokenRequest request, Authentication authentication) {

    if (request == null || request.path() == null || request.path().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "path is required"));
    }

    String username = authentication.getName();
    String token = mediaTokenService.generateToken(username, request.path());

    return ResponseEntity.ok(
        Map.of("token", token, "expiresIn", mediaTokenService.getTtlSeconds()));
  }

  /**
   * Streams the file a media token was issued for.
   *
   * <p>The path is read from the token, never from the query string, so a holder cannot redirect a
   * valid token at a different file. Range and conditional headers are forwarded upstream and the
   * upstream status is preserved, which is what allows {@code 206 Partial Content} and {@code 304
   * Not Modified} to reach the browser intact.
   */
  @GetMapping("/stream/{token}")
  public ResponseEntity<StreamingResponseBody> stream(
      @PathVariable String token,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
      @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false)
          String ifModifiedSince) {

    final Claims claims;
    try {
      claims = mediaTokenService.parseToken(token);
    } catch (JwtException e) {
      // Expired or tampered tokens are an expected condition, not a server fault: a tab left
      // open past the token lifetime will land here. The client re-mints and retries.
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String username = claims.getSubject();
    String path = mediaTokenService.extractPath(claims);

    try {
      HttpResponse<InputStream> upstream =
          cloudPageMediaClient.fetch(username, path, range, ifNoneMatch, ifModifiedSince);

      ResponseEntity.BodyBuilder response = ResponseEntity.status(upstream.statusCode());

      for (String header : FORWARDED_RESPONSE_HEADERS) {
        upstream.headers().firstValue(header).ifPresent(value -> response.header(header, value));
      }

      // A 304 carries no body, and reading the (empty) stream would only add a hop.
      if (upstream.statusCode() == HttpStatus.NOT_MODIFIED.value()) {
        upstream.body().close();
        return response.build();
      }

      StreamingResponseBody body = outputStream -> copy(upstream.body(), outputStream);
      return response.body(body);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    } catch (Exception e) {
      log.error("Failed to stream media for user {}", username, e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }

  /**
   * Pumps upstream bytes to the client.
   *
   * <p>The stream is closed on the way out whatever happens. Aborted playback and seeks show up
   * here as a broken pipe partway through a copy — that is ordinary browser behaviour, so it is
   * swallowed rather than logged as an error.
   */
  private void copy(InputStream source, OutputStream target) {
    try (InputStream in = source) {
      in.transferTo(target);
    } catch (Exception e) {
      log.debug("Media stream ended early (client likely seeked or navigated away)", e);
    }
  }

  /** Request body for {@link #createMediaToken}. */
  public record MediaTokenRequest(String path) {}
}
