package vaultWeb.security.pat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vaultWeb.security.JwtAuthenticationEntryPoint;
import vaultWeb.services.auth.PersonalAccessTokenService;

/**
 * Authenticates {@code Authorization: Bearer vw_pat_...} requests.
 *
 * <p>Requests without a PAT pass through untouched (JWT handling stays in {@code JwtAuthFilter}).
 * Invalid, expired or revoked PATs get a generic 401. Valid PATs are throttled per token and per
 * owner; the keys come from the verified token, so they cannot be spoofed by the client.
 */
@Component
public class PatAuthFilter extends OncePerRequestFilter {

  private final PersonalAccessTokenService tokenService;
  private final JwtAuthenticationEntryPoint entryPoint;
  private final int perTokenLimit;
  private final int perOwnerLimit;

  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).maximumSize(10_000).build();

  public PatAuthFilter(
      PersonalAccessTokenService tokenService,
      JwtAuthenticationEntryPoint entryPoint,
      @Value("${app.pat.rate-limit-per-minute:60}") int perTokenLimit,
      @Value("${app.pat.owner-rate-limit-per-minute:120}") int perOwnerLimit) {
    this.tokenService = tokenService;
    this.entryPoint = entryPoint;
    this.perTokenLimit = perTokenLimit;
    this.perOwnerLimit = perOwnerLimit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (!PatTokens.isBearerPat(header)) {
      filterChain.doFilter(request, response);
      return;
    }

    Optional<PatAuthenticationToken> authentication =
        tokenService.authenticate(header.substring("Bearer ".length()));
    if (authentication.isEmpty()) {
      SecurityContextHolder.clearContext();
      entryPoint.commence(
          request, response, new BadCredentialsException("Invalid personal access token"));
      return;
    }

    PatAuthenticationToken pat = authentication.get();
    Long retryAfter =
        firstExhausted(
            bucket("PAT:" + pat.getPublicId(), perTokenLimit),
            bucket("PAT_OWNER:" + pat.getUserId(), perOwnerLimit));
    if (retryAfter != null) {
      writeTooManyRequests(response, retryAfter);
      return;
    }

    pat.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(pat);
    filterChain.doFilter(request, response);
  }

  private Bucket bucket(String key, int perMinute) {
    return buckets.get(
        key,
        k ->
            Bucket.builder()
                .addLimit(
                    Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1))
                        .build())
                .build());
  }

  /** Consumes from each bucket in order; returns retry-after seconds for the first exhausted. */
  private static Long firstExhausted(Bucket... toConsume) {
    for (Bucket bucket : toConsume) {
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      if (!probe.isConsumed()) {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
      }
    }
    return null;
  }

  private static void writeTooManyRequests(HttpServletResponse response, long retryAfter)
      throws IOException {
    response.setStatus(429);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    response
        .getWriter()
        .write(
            "{\"code\":\"RATE_LIMITED\",\"message\":\"Too Many Requests\",\"timestamp\":\""
                + Instant.now()
                + "\"}");
  }
}
