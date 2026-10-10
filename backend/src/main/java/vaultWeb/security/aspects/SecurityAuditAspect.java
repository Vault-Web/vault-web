package vaultWeb.security.aspects;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.dtos.user.LoginRequest;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.annotations.AuditSecurityEvent;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.NotificationService;

/**
 * Aspect that logs security-relevant events for audit purposes.
 *
 * <p>This aspect intercepts methods annotated with {@link AuditSecurityEvent} and logs details
 * including username, event type, timestamp, IP address, and success/failure status.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class SecurityAuditAspect {

  private static final Logger log = LoggerFactory.getLogger(SecurityAuditAspect.class);
  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final SecurityEventRepository securityEventRepository;
  private final NotificationService notificationService;

  /**
   * Logs successful security operations.
   *
   * @param joinPoint the join point providing access to the method being invoked
   * @param auditSecurityEvent the annotation containing the event type
   */
  @AfterReturning(pointcut = "@annotation(auditSecurityEvent)", returning = "result")
  public void logSuccess(
      JoinPoint joinPoint, AuditSecurityEvent auditSecurityEvent, Object result) {
    String deviceId = null;
    if (auditSecurityEvent.value() == SecurityEventType.NEW_DEVICE_DETECTED
        && result instanceof DeviceDto deviceDto) {
      if (!isNewDeviceRegistration(deviceDto)) {
        return;
      }
      deviceId = deviceDto.getDeviceId();
    }
    String status = deriveStatus(result);
    logSecurityEvent(joinPoint, auditSecurityEvent.value(), status, null, deviceId);
  }

  /**
   * Logs failed security operations.
   *
   * @param joinPoint the join point providing access to the method being invoked
   * @param auditSecurityEvent the annotation containing the event type
   * @param ex the throwable that was thrown
   */
  @AfterThrowing(pointcut = "@annotation(auditSecurityEvent)", throwing = "ex")
  public void logFailure(JoinPoint joinPoint, AuditSecurityEvent auditSecurityEvent, Throwable ex) {
    logSecurityEvent(joinPoint, auditSecurityEvent.value(), "FAILURE", ex, null);
  }

  private boolean isNewDeviceRegistration(DeviceDto deviceDto) {
    return deviceDto.getCreatedAt() != null
        && deviceDto.getLastSeen() != null
        && deviceDto.getCreatedAt().equals(deviceDto.getLastSeen());
  }

  private String deriveStatus(Object result) {
    if (result instanceof ResponseEntity<?> response) {
      return response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILURE";
    }
    return "SUCCESS";
  }

  private void logSecurityEvent(
      JoinPoint joinPoint,
      SecurityEventType eventType,
      String status,
      Throwable ex,
      String deviceId) {
    HttpServletRequest request = getRequest();
    String ip = getClientIp(request);
    String username = extractUsername(joinPoint, request);
    Instant timestamp = Instant.now();
    String userAgent = request != null ? request.getHeader("User-Agent") : "unknown";

    if (ex == null) {
      log.info(
          "SECURITY_EVENT: type={}, username={}, ip={}, timestamp={}, status={}",
          eventType,
          username,
          ip,
          timestamp,
          status);
    } else {
      log.warn(
          "SECURITY_EVENT: type={}, username={}, ip={}, timestamp={}, status={}, error={}",
          eventType,
          username,
          ip,
          timestamp,
          status,
          ex.getClass().getSimpleName());
    }

    try {
      User user = userRepository.findByUsername(username).orElse(null);
      SecurityEvent event = new SecurityEvent();
      event.setUser(user);
      event.setUsername(username);
      event.setEventType(eventType);
      event.setStatus(status);
      event.setTimestamp(timestamp);
      event.setIpAddress(ip);
      event.setDeviceId(deviceId);
      event.setUserAgent(userAgent != null ? userAgent : "unknown");
      event.setLocation("Unknown");
      SecurityEvent savedEvent = securityEventRepository.save(event);
      notificationService.publishSecurityEvent(savedEvent);
    } catch (Exception e) {
      log.error("Failed to save security event to database", e);
    }
  }

  private String extractUsername(JoinPoint joinPoint, HttpServletRequest request) {
    String username;

    // 1. SecurityContext (for authenticated endpoints: changePassword)
    username = extractFromSecurityContext();
    if (username != null && !username.isBlank()) {
      return username;
    }

    // 2. JWT Bearer header (for endpoints with access token)
    if (request != null) {
      username = jwtUtil.extractUsernameFromRequest(request);
      if (username != null && !username.isBlank()) {
        return username;
      }
    }

    // 3. Refresh token cookie (for refresh/logout endpoints)
    if (request != null) {
      username = extractFromRefreshTokenCookie(request);
      if (username != null && !username.isBlank()) {
        return username;
      }
    }

    // 4. Method arguments (UserDto/LoginRequest for login/register)
    for (Object arg : joinPoint.getArgs()) {
      if (arg instanceof UserDto userDto
          && userDto.getUsername() != null
          && !userDto.getUsername().isBlank()) {
        return userDto.getUsername();
      }
      if (arg instanceof LoginRequest loginRequest
          && loginRequest.getUsername() != null
          && !loginRequest.getUsername().isBlank()) {
        return loginRequest.getUsername();
      }
    }

    return "anonymous";
  }

  private String extractFromSecurityContext() {
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null
          && authentication.isAuthenticated()
          && authentication.getPrincipal() instanceof UserDetails userDetails) {
        return userDetails.getUsername();
      }
    } catch (Exception e) {
      log.trace("Could not extract username from SecurityContext", e);
    }
    return null;
  }

  private String extractFromRefreshTokenCookie(HttpServletRequest request) {
    try {
      Cookie[] cookies = request.getCookies();
      if (cookies == null) {
        return null;
      }
      for (Cookie cookie : cookies) {
        if ("refresh_token".equals(cookie.getName())) {
          String token = cookie.getValue();
          if (token != null && !token.isBlank()) {
            Claims claims = jwtUtil.parseRefreshToken(token);
            Long userId = Long.parseLong(claims.getSubject());
            return userRepository.findById(userId).map(user -> user.getUsername()).orElse(null);
          }
        }
      }
    } catch (Exception e) {
      log.trace("Could not extract username from refresh token cookie", e);
    }
    return null;
  }

  /**
   * Extracts client IP address from the request.
   *
   * <p>This method intentionally does not read proxy headers such as {@code X-Forwarded-For} to
   * avoid trusting potentially spoofed client IPs. If the application is deployed behind a reverse
   * proxy, rely on the server/framework configuration (for example Spring's forwarded-header
   * support) to ensure {@link HttpServletRequest#getRemoteAddr()} already reflects the correct
   * client address.
   */
  private String getClientIp(HttpServletRequest request) {
    if (request == null) {
      return "unknown";
    }
    return request.getRemoteAddr();
  }

  private HttpServletRequest getRequest() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
      return attrs.getRequest();
    } catch (IllegalStateException e) {
      log.warn("Unable to retrieve HttpServletRequest context for security audit");
      return null;
    }
  }
}
