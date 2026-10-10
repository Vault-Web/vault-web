package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import vaultWeb.models.Notification;
import vaultWeb.models.User;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.NotificationPreferenceRepository;
import vaultWeb.repositories.NotificationRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;
  @Mock private UserRepository userRepository;
  @Mock private PrivateChatRepository privateChatRepository;
  @Mock private GroupMemberRepository groupMemberRepository;
  @InjectMocks private NotificationService service;

  @Test
  void unreadCountIsAlwaysScopedToCurrentUser() {
    User user = new User();
    user.setUsername("alice");
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(notificationRepository.countByUserAndReadAtIsNull(user)).thenReturn(3L);

    assertEquals(3L, service.unreadCount("alice"));
    verify(notificationRepository).countByUserAndReadAtIsNull(user);
  }

  @Test
  void markReadDoesNotAllowAccessToAnotherUsersNotification() {
    User alice = new User();
    alice.setUsername("alice");
    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(notificationRepository.findByIdAndUser(10L, alice)).thenReturn(Optional.empty());

    org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class, () -> service.markRead("alice", 10L, true));
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void mutedSourceSuppressesNotificationCreation() {
    User alice = new User();
    alice.setId(4L);
    when(preferenceRepository.findByUserAndSource(alice, "SECURITY"))
        .thenReturn(Optional.of(mutedPreference(alice)));

    service.publish(alice, "SECURITY", "LOGIN", "New login", "Login recorded.",
        "/security-activity", "1");

    verify(notificationRepository, never()).save(any());
  }

  @Test
  void notificationNeverRequiresOrCopiesPayloadContent() {
    User alice = new User();
    alice.setId(4L);
    when(preferenceRepository.findByUserAndSource(alice, "CHAT")).thenReturn(Optional.empty());
    when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.publish(alice, "CHAT", "NEW_MESSAGE", "New private message",
        "You received a new encrypted message.", "/", "42");

    var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    assertEquals("You received a new encrypted message.", captor.getValue().getMessage());
    assertEquals(Instant.class, captor.getValue().getCreatedAt().getClass());
  }

  private vaultWeb.models.NotificationPreference mutedPreference(User user) {
    var preference = new vaultWeb.models.NotificationPreference();
    preference.setUser(user);
    preference.setSource("SECURITY");
    preference.setMuted(true);
    return preference;
  }
}
