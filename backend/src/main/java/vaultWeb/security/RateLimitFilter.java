package vaultWeb.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

  // Caffeine cache with eviction policy
  private final Cache<String, Bucket> cache =
      Caffeine.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(5)) // cleanup idle entries
          .maximumSize(10_000) // safety limit
          .build();

  @Value("${app.rate-limit-per-minute:100}")
  private Integer rateLimit;

  @Autowired private JwtUtil jwtUtil;

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String clientId = httpRequest.getRemoteAddr();

    // If IP is missing or blank (rare, but possible behind proxies)
    if (clientId == null || clientId.isBlank()) {
      clientId = jwtUtil.extractUsernameFromRequest(httpRequest);
    }

    // Last fallback if both failed
    if (clientId == null || clientId.isBlank()) {
      clientId = "unknown";
    }

    Bucket bucket = resolveBucket(clientId);
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

    if (probe.isConsumed()) {
      httpResponse.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
      chain.doFilter(request, response);
    } else {
      long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
      if (retryAfter == 0) {
        retryAfter = 1;
      }
      httpResponse.setStatus(429); // 429
      httpResponse.setContentType("application/json");
      httpResponse.addHeader("Retry-After", String.valueOf(retryAfter));
      httpResponse.addHeader("X-RateLimit-Limit", String.valueOf(rateLimit));
      httpResponse.addHeader("X-RateLimit-Remaining", "0");

      // Send JSON error response
      String errorJson =
          String.format("{\"error\":\"Rate limit exceeded\",\"retryAfter\":%d}", retryAfter);
      httpResponse.getWriter().write(errorJson);
    }
  }

  private Bucket resolveBucket(String clientId) {
    // Caffeine auto-creates and caches buckets
    return cache.get(clientId, k -> createNewBucket());
  }

  private Bucket createNewBucket() {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(rateLimit)
            .refillGreedy(rateLimit, Duration.ofMinutes(1))
            .build();

    return Bucket.builder().addLimit(limit).build();
  }
}
