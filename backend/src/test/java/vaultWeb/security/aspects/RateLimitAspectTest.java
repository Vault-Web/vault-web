package vaultWeb.security.aspects;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vaultWeb.exceptions.RateLimitExceededException;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.annotations.ApiRateLimit;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

  @Mock private JwtUtil jwtUtil;
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  @Mock private ProceedingJoinPoint joinPoint;
  @Mock private ApiRateLimit apiRateLimit;

  @InjectMocks private RateLimitAspect rateLimitAspect;

  @BeforeEach
  void setUp() {
    ServletRequestAttributes attrs = new ServletRequestAttributes(request);
    RequestContextHolder.setRequestAttributes(attrs);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void forwardedHeaderDoesNotChangeTheAddressBucket() throws Throwable {
    request.setRemoteAddr("192.0.2.1");
    when(apiRateLimit.useIpAddress()).thenReturn(true);
    when(apiRateLimit.capacity()).thenReturn(1);
    when(apiRateLimit.refillTokens()).thenReturn(1);
    when(apiRateLimit.refillDurationMinutes()).thenReturn(1);
    rateLimitAspect.rateLimit(joinPoint, apiRateLimit);

    request.addHeader("X-Forwarded-For", "198.51.100.1");
    assertThatThrownBy(() -> rateLimitAspect.rateLimit(joinPoint, apiRateLimit))
        .isInstanceOf(RateLimitExceededException.class);

    request.setRemoteAddr("192.0.2.2");
    rateLimitAspect.rateLimit(joinPoint, apiRateLimit);
    verify(joinPoint, times(2)).proceed();
  }
}
