package vaultWeb.security.aspects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vaultWeb.exceptions.RateLimitExceededException;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.annotations.ApiRateLimit;

@Aspect
@Component
@Profile("!test")
public class RateLimitAspect {

  // Caffeine Cache with eviction
  private final Cache<String, Bucket> cache =
      Caffeine.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(5)) // remove idle buckets
          .maximumSize(10_000) // safety cap
          .build();

  @Autowired private JwtUtil jwtUtil;

  @Around("@annotation(apiRateLimit)")
  public Object rateLimit(ProceedingJoinPoint joinPoint, ApiRateLimit apiRateLimit)
      throws Throwable {

    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();

    String key = getRateLimitKey(request, apiRateLimit);
    Bucket bucket = resolveBucket(key, apiRateLimit);

    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

    if (probe.isConsumed()) {
      request.setAttribute("X-Rate-Limit-Remaining", probe.getRemainingTokens());
      return joinPoint.proceed();
    } else {
      long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
      if (retryAfter == 0) {
        retryAfter = 1;
      }
      throw new RateLimitExceededException(
          "Rate limit exceeded. Try again in " + retryAfter + " seconds", retryAfter);
    }
  }

  private Bucket resolveBucket(String key, ApiRateLimit rateLimit) {
    return cache.get(
        key,
        k -> {
          Bandwidth limit =
              Bandwidth.builder()
                  .capacity(rateLimit.capacity())
                  .refillGreedy(
                      rateLimit.refillTokens(),
                      Duration.ofMinutes(rateLimit.refillDurationMinutes()))
                  .build();

          return Bucket.builder().addLimit(limit).build();
        });
  }

  private String getRateLimitKey(HttpServletRequest request, ApiRateLimit rateLimit) {
    // Tomcat resolves forwarded addresses only when its trusted proxy list is enabled.
    String ip = request.getRemoteAddr();
    if (rateLimit.useIpAddress()) {
      return "IP:" + ip;
    }

    String username = jwtUtil.extractUsernameFromRequest(request);
    if (username != null && !username.isBlank()) {
      return "USER:" + username;
    }
    return "ANON:" + ip;
  }
}
