package vaultWeb.services;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Streams file bytes out of the cloud-page service on behalf of a user.
 *
 * <p>The request is forwarded rather than proxied blindly: this client mints a short-lived
 * cloud-page access token for the named user, so cloud-page performs its own ownership and
 * path-traversal checks exactly as it would for a direct call. The proxy therefore never widens
 * what a user can reach.
 *
 * <p>Responses are returned as a live {@link InputStream}. Nothing is buffered into memory, which
 * is the whole point of the exercise: a multi-gigabyte video must not be materialised on the
 * vault-web heap in order to serve a 500 KB range request.
 */
@Service
public class CloudPageMediaClient {

  private static final Logger log = LoggerFactory.getLogger(CloudPageMediaClient.class);

  /**
   * Lifetime of the internal cloud-page token. This token never leaves the server, so it only needs
   * to outlive a single forwarded request.
   */
  private static final long INTERNAL_TOKEN_TTL_MILLIS = 60_000;

  private final SecretKey cloudPageKey;

  private final String cloudPageBaseUrl;

  private final HttpClient httpClient;

  public CloudPageMediaClient(
      @Value("${jwt.secret}") String sharedSecret,
      @Value("${app.cloudpage.base-url}") String cloudPageBaseUrl,
      @Value("${app.cloudpage.trust-self-signed:false}") boolean trustSelfSigned) {
    this.cloudPageKey = Keys.hmacShaKeyFor(sharedSecret.getBytes());
    this.cloudPageBaseUrl = stripTrailingSlash(cloudPageBaseUrl);
    this.httpClient = buildHttpClient(trustSelfSigned);
  }

  /**
   * Forwards a view request for a single file to cloud-page.
   *
   * @param username the user whose storage the file is read from
   * @param path the user-root-relative file path
   * @param rangeHeader the inbound {@code Range} header, or {@code null} if absent
   * @param ifNoneMatch the inbound {@code If-None-Match} header, or {@code null}
   * @param ifModifiedSince the inbound {@code If-Modified-Since} header, or {@code null}
   * @return the upstream response, its body still streaming
   */
  public HttpResponse<InputStream> fetch(
      String username, String path, String rangeHeader, String ifNoneMatch, String ifModifiedSince)
      throws IOException, InterruptedException {

    URI uri =
        URI.create(
            cloudPageBaseUrl
                + "/api/files/view?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8));

    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer " + mintInternalToken(username));

    // Conditional and range headers are passed straight through: they are what make seeking
    // and browser caching work, and cloud-page is the component that knows how to answer them.
    if (rangeHeader != null && !rangeHeader.isBlank()) {
      request.header("Range", rangeHeader);
    }
    if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
      request.header("If-None-Match", ifNoneMatch);
    }
    if (ifModifiedSince != null && !ifModifiedSince.isBlank()) {
      request.header("If-Modified-Since", ifModifiedSince);
    }

    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  /** Project references use the caller's actual credentials, never an owner's token. */
  public HttpResponse<InputStream> fetchProjectReference(String endpoint, String authorization)
      throws IOException, InterruptedException {
    return fetchProjectReference(endpoint, authorization, Duration.ofSeconds(10));
  }

  public HttpResponse<InputStream> fetchProjectReference(
      String endpoint, String authorization, Duration timeout)
      throws IOException, InterruptedException {
    if (!endpoint.startsWith("/api/folders/path?")
        && !endpoint.startsWith("/api/files/view?")
        && !endpoint.equals("/api/shares/shared-with-me")
        && !endpoint.matches("/api/shares/[a-zA-Z0-9-]+/(view|content)")) {
      throw new IllegalArgumentException("Unsupported project reference endpoint");
    }
    var pending =
        httpClient.sendAsync(
            HttpRequest.newBuilder(URI.create(cloudPageBaseUrl + endpoint))
                .timeout(timeout)
                .GET()
                .header("Authorization", authorization)
                .header("Range", "bytes=0-0")
                .build(),
            info ->
                HttpResponse.BodySubscribers.mapping(
                    new BoundedBodySubscriber(),
                    bytes -> (InputStream) new java.io.ByteArrayInputStream(bytes)));
    try {
      return pending.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.TimeoutException ex) {
      pending.cancel(true);
      throw new java.net.http.HttpTimeoutException("Project reference request timed out");
    } catch (java.util.concurrent.ExecutionException ex) {
      if (ex.getCause() instanceof IOException io) throw io;
      throw new IOException("Project reference request failed", ex.getCause());
    } catch (InterruptedException ex) {
      pending.cancel(true);
      throw ex;
    }
  }

  /** Finish the bounded body before returning so the request timeout covers slow bodies too. */
  private static class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private java.util.concurrent.Flow.Subscription subscription;
    private int bytes;

    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
      subscription = value;
      delegate.onSubscribe(value);
    }

    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > 1_048_576 - bytes) {
          subscription.cancel();
          delegate.onError(new IOException("Project reference response too large"));
          return;
        }
        bytes += buffer.remaining();
      }
      delegate.onNext(buffers);
    }

    public void onError(Throwable error) {
      delegate.onError(error);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }

  /**
   * Mints a cloud-page access token for the given user.
   *
   * <p>Both services are configured with the same {@code jwt.secret}, so a token minted here is
   * accepted by cloud-page's authentication filter. The subject claim carries the username, which
   * is what cloud-page resolves the storage root from.
   */
  private String mintInternalToken(String username) {
    Instant now = Instant.now();
    return Jwts.builder()
        .setSubject(username)
        .setIssuedAt(Date.from(now))
        .setExpiration(new Date(now.toEpochMilli() + INTERNAL_TOKEN_TTL_MILLIS))
        .signWith(cloudPageKey, SignatureAlgorithm.HS256)
        .compact();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * Builds the HTTP client.
   *
   * <p>cloud-page ships a self-signed certificate for local development. When {@code
   * app.cloudpage.trust-self-signed} is enabled the client skips certificate verification so
   * developers are not forced to import that certificate into a trust store. The flag defaults to
   * {@code false} and must stay that way outside local development.
   */
  private static HttpClient buildHttpClient(boolean trustSelfSigned) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);

    if (trustSelfSigned) {
      log.warn(
          "cloud-page TLS verification is DISABLED (app.cloudpage.trust-self-signed=true). "
              + "This is intended for local development only.");
      builder.sslContext(insecureSslContext());
    }
    return builder.build();
  }

  private static SSLContext insecureSslContext() {
    try {
      TrustManager[] trustAll =
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustAll, new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to build development SSL context", e);
    }
  }
}
