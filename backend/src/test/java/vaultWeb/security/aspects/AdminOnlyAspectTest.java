package vaultWeb.security.aspects;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vaultWeb.exceptions.AdminAccessDeniedException;
import vaultWeb.models.GroupMember;
import vaultWeb.models.User;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.services.auth.AuthService;

@ExtendWith(MockitoExtension.class)
class AdminOnlyAspectTest {

  @Mock private AuthService authService;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private JoinPoint joinPoint;

  @InjectMocks private AdminOnlyAspect adminOnlyAspect;

  private static final Long GROUP_ID = 1L;
  private static final Long USER_ID = 2L;

  @Test
  void checkAdmin_NotAuthenticated_ThrowsAdminAccessDeniedException() {
    // Arrange
    when(authService.getCurrentUser()).thenReturn(null);

    // Act & Assert
    assertThatThrownBy(() -> adminOnlyAspect.checkAdmin(joinPoint))
        .isInstanceOf(AdminAccessDeniedException.class)
        .hasMessage("Not authenticated.");
  }

  @Test
  void checkAdmin_NonMember_ThrowsAdminAccessDeniedException() {
    // Arrange
    User currentUser = new User();
    currentUser.setId(USER_ID);
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(joinPoint.getArgs()).thenReturn(new Object[] {GROUP_ID});
    when(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> adminOnlyAspect.checkAdmin(joinPoint))
        .isInstanceOf(AdminAccessDeniedException.class)
        .hasMessage("Admin privileges for this group required.");
  }

  @Test
  void checkAdmin_NonAdminMember_ThrowsAdminAccessDeniedException() {
    // Arrange
    User currentUser = new User();
    currentUser.setId(USER_ID);
    GroupMember groupMember = GroupMember.builder().role(Role.USER).build();
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(joinPoint.getArgs()).thenReturn(new Object[] {GROUP_ID});
    when(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .thenReturn(Optional.of(groupMember));

    // Act & Assert
    assertThatThrownBy(() -> adminOnlyAspect.checkAdmin(joinPoint))
        .isInstanceOf(AdminAccessDeniedException.class)
        .hasMessage("Admin privileges for this group required.");
  }

  @Test
  void checkAdmin_AdminMember_ProceedsWithoutException() {
    // Arrange
    User currentUser = new User();
    currentUser.setId(USER_ID);
    GroupMember groupMember = GroupMember.builder().role(Role.ADMIN).build();
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(joinPoint.getArgs()).thenReturn(new Object[] {GROUP_ID});
    when(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .thenReturn(Optional.of(groupMember));

    // Act & Assert: no exception should be thrown
    adminOnlyAspect.checkAdmin(joinPoint);
  }

  @Test
  void checkAdmin_FirstArgumentNotLong_ThrowsIllegalArgumentException() {
    // Arrange
    User currentUser = new User();
    currentUser.setId(USER_ID);
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(joinPoint.getArgs()).thenReturn(new Object[] {"not-a-long"});

    // Act & Assert
    assertThatThrownBy(() -> adminOnlyAspect.checkAdmin(joinPoint))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Method with @AdminOnly must have groupId (Long) as first argument.");
  }

  @Test
  void checkAdmin_NoArguments_ThrowsIllegalArgumentException() {
    // Arrange
    User currentUser = new User();
    currentUser.setId(USER_ID);
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(joinPoint.getArgs()).thenReturn(new Object[] {});

    // Act & Assert
    assertThatThrownBy(() -> adminOnlyAspect.checkAdmin(joinPoint))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Method with @AdminOnly must have groupId (Long) as first argument.");
  }
}
