package vaultWeb.security.aspects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.models.SecurityEvent;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.annotations.AuditSecurityEvent;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.NotificationService;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SecurityAuditAspectTest {

  @Mock private JwtUtil jwtUtil;
  @Mock private JoinPoint joinPoint;
  @Mock private AuditSecurityEvent auditSecurityEvent;
  @Mock private HttpServletRequest request;
  @Mock private UserRepository userRepository;
  @Mock private SecurityEventRepository securityEventRepository;
  @Mock private NotificationService notificationService;

  @InjectMocks private SecurityAuditAspect securityAuditAspect;

  private ListAppender<ILoggingEvent> listAppender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    // Set up log capturing
    logger = (Logger) LoggerFactory.getLogger(SecurityAuditAspect.class);
    listAppender = new ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);

    // Set up request context
    ServletRequestAttributes attrs = new ServletRequestAttributes(request);
    RequestContextHolder.setRequestAttributes(attrs);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
    logger.detachAppender(listAppender);
  }

  @Test
  void logSuccess_withAuthenticatedUser_logsSuccessEvent() {
    // Arrange
    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.LOGOUT);
    when(request.getRemoteAddr()).thenReturn("192.168.1.1");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn("testuser");
    when(joinPoint.getArgs()).thenReturn(new Object[] {});

    // Act
    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, ResponseEntity.ok().build());

    // Assert
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
    assertThat(logEvent.getFormattedMessage()).contains("SECURITY_EVENT");
    assertThat(logEvent.getFormattedMessage()).contains("type=LOGOUT");
    assertThat(logEvent.getFormattedMessage()).contains("username=testuser");
    assertThat(logEvent.getFormattedMessage()).contains("ip=192.168.1.1");
    assertThat(logEvent.getFormattedMessage()).contains("status=SUCCESS");
  }

  @Test
  void logSuccess_withUserDtoArgument_extractsUsernameFromDto() {
    // Arrange
    UserDto userDto = new UserDto();
    userDto.setUsername("newuser");
    userDto.setPassword("password123");

    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.LOGIN);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn(null);
    when(joinPoint.getArgs()).thenReturn(new Object[] {userDto});

    // Act
    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, ResponseEntity.ok().build());

    // Assert
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getFormattedMessage()).contains("username=newuser");
    assertThat(logEvent.getFormattedMessage()).contains("type=LOGIN");
  }

  @Test
  void logSuccess_withXForwardedForHeader_usesRemoteAddrNotForwardedIp() {
    // Arrange - aspect intentionally ignores X-Forwarded-For to avoid trusting spoofed headers
    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.REGISTER);
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn(null);
    when(joinPoint.getArgs()).thenReturn(new Object[] {});

    // Act
    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, ResponseEntity.ok().build());

    // Assert - should use remoteAddr, not X-Forwarded-For
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getFormattedMessage()).contains("ip=127.0.0.1");
  }

  @Test
  void logFailure_logsWarningWithExceptionType() {
    // Arrange
    Exception exception = new RuntimeException("Test error");
    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.LOGIN);
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn(null);

    UserDto userDto = new UserDto();
    userDto.setUsername("faileduser");
    userDto.setPassword("wrongpass");
    when(joinPoint.getArgs()).thenReturn(new Object[] {userDto});

    // Act
    securityAuditAspect.logFailure(joinPoint, auditSecurityEvent, exception);

    // Assert
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getLevel()).isEqualTo(Level.WARN);
    assertThat(logEvent.getFormattedMessage()).contains("SECURITY_EVENT");
    assertThat(logEvent.getFormattedMessage()).contains("type=LOGIN");
    assertThat(logEvent.getFormattedMessage()).contains("username=faileduser");
    assertThat(logEvent.getFormattedMessage()).contains("status=FAILURE");
    assertThat(logEvent.getFormattedMessage()).contains("error=RuntimeException");
  }

  @Test
  void logSuccess_withNoUsernameSource_logsAnonymous() {
    // Arrange
    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.TOKEN_REFRESH);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn(null);
    when(joinPoint.getArgs()).thenReturn(new Object[] {"some-token"});

    // Act
    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, ResponseEntity.ok().build());

    // Assert
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getFormattedMessage()).contains("username=anonymous");
  }

  @Test
  void logSuccess_withPasswordChangeEvent_logsCorrectEventType() {
    // Arrange
    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.PASSWORD_CHANGE);
    when(request.getRemoteAddr()).thenReturn("192.168.0.1");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn("existinguser");
    when(joinPoint.getArgs()).thenReturn(new Object[] {});

    // Act
    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, ResponseEntity.ok().build());

    // Assert
    assertThat(listAppender.list).hasSize(1);
    ILoggingEvent logEvent = listAppender.list.get(0);
    assertThat(logEvent.getFormattedMessage()).contains("type=PASSWORD_CHANGE");
    assertThat(logEvent.getFormattedMessage()).contains("username=existinguser");
    assertThat(logEvent.getFormattedMessage()).contains("status=SUCCESS");
  }

  @Test
  void logSuccess_withNewDeviceDetected_persistsDeviceId() {
    DeviceDto deviceDto =
        new DeviceDto(
            "device-1", "public-key", 1L, "alice", "2026-07-07T07:00:00Z", "2026-07-07T07:00:00Z");

    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.NEW_DEVICE_DETECTED);
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(request.getHeader("User-Agent")).thenReturn("JUnit");
    when(jwtUtil.extractUsernameFromRequest(request)).thenReturn("alice");
    when(joinPoint.getArgs()).thenReturn(new Object[] {});

    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, deviceDto);

    var eventCaptor = org.mockito.ArgumentCaptor.forClass(SecurityEvent.class);
    verify(securityEventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getDeviceId()).isEqualTo("device-1");
    assertThat(eventCaptor.getValue().getEventType())
        .isEqualTo(SecurityEventType.NEW_DEVICE_DETECTED);
  }

  @Test
  void logSuccess_withExistingDeviceRegistration_doesNotLogNewDeviceEvent() {
    DeviceDto deviceDto =
        new DeviceDto(
            "device-1", "public-key", 1L, "alice", "2026-07-07T07:00:00Z", "2026-07-07T07:05:00Z");

    when(auditSecurityEvent.value()).thenReturn(SecurityEventType.NEW_DEVICE_DETECTED);

    securityAuditAspect.logSuccess(joinPoint, auditSecurityEvent, deviceDto);

    assertThat(listAppender.list).isEmpty();
    verifyNoInteractions(securityEventRepository);
  }
}
