package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.Notification;
import vaultWeb.models.NotificationPreference;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.NotificationPreferenceRepository;
import vaultWeb.repositories.NotificationRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.annotations.SecurityEventType;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;
  @Mock private UserRepository userRepository;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private ChatMessageRepository chatMessageRepository;

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

    assertThrows(
        ResponseStatusException.class, () -> service.markRead("alice", 10L, true));
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void mutedNonCriticalSourceSuppressesNotificationCreation() {
    User alice = user(4L, "alice");
    when(preferenceRepository.findByUserAndSource(alice, "SECURITY"))
        .thenReturn(Optional.of(preference(alice, "SECURITY", true)));

    service.publish(
        alice, "SECURITY", "LOGIN", "New login", "Login recorded.", "/security-activity", "1");

    verify(notificationRepository, never()).save(any());
  }

  @Test
  void criticalSecurityAlertIsDeliveredEvenWhenSecuritySourceIsMuted() {
    User alice = user(4L, "alice");
    when(preferenceRepository.findByUserAndSource(alice, "SECURITY"))
        .thenReturn(Optional.of(preference(alice, "SECURITY", true)));
    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.publish(
        alice,
        "SECURITY",
        "PASSWORD_CHANGE",
        "Password changed",
        "Your account password was changed.",
        "/security-activity",
        "12");

    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    assertEquals("PASSWORD_CHANGE", captor.getValue().getType());
  }

  @Test
  void chatFanOutExcludesSenderAndStoresOnlyGenericEncryptedMessageText() {
    User sender = user(1L, "sender");
    User recipient = user(2L, "recipient");
    Group group = new Group();
    group.setId(77L);
    group.setName("Study Group");

    GroupMember senderMember = new GroupMember();
    senderMember.setUser(sender);
    GroupMember recipientMember = new GroupMember();
    recipientMember.setUser(recipient);

    ChatMessage message = new ChatMessage();
    message.setId(900L);
    message.setSender(sender);
    message.setGroup(group);
    message.setE2eePayload("TOP-SECRET-CIPHERTEXT");

    when(chatMessageRepository.findById(900L)).thenReturn(Optional.of(message));
    when(groupMemberRepository.findAllByGroup(group))
        .thenReturn(List.of(senderMember, recipientMember));
    when(preferenceRepository.findByUserInAndSource(anyCollection(), eq("CHAT")))
        .thenReturn(List.of());
    when(notificationRepository
            .findByUserInAndSourceAndTypeAndReferenceIdAndReadAtIsNull(
                anyCollection(), eq("CHAT"), eq("NEW_MESSAGE"), eq("77")))
        .thenReturn(List.of());

    service.enqueueChatMessage(900L);

    ArgumentCaptor<Iterable<Notification>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(notificationRepository).saveAll(captor.capture());
    List<Notification> saved = new ArrayList<>();
    captor.getValue().forEach(saved::add);

    assertEquals(1, saved.size());
    assertEquals(recipient, saved.get(0).getUser());
    assertEquals("77", saved.get(0).getReferenceId());
    assertEquals("A new encrypted message was posted to Study Group", saved.get(0).getMessage());
    org.junit.jupiter.api.Assertions.assertFalse(
        saved.get(0).getMessage().contains(message.getE2eePayload()));
  }

  @Test
  void existingUnreadChatNotificationIsCoalesced() {
    User sender = user(1L, "sender");
    User recipient = user(2L, "recipient");
    Group group = new Group();
    group.setId(77L);
    group.setName("Study Group");

    GroupMember senderMember = new GroupMember();
    senderMember.setUser(sender);
    GroupMember recipientMember = new GroupMember();
    recipientMember.setUser(recipient);

    Notification existing = new Notification();
    existing.setUser(recipient);
    existing.setSource("CHAT");
    existing.setType("NEW_MESSAGE");
    existing.setReferenceId("77");

    ChatMessage message = new ChatMessage();
    message.setId(901L);
    message.setSender(sender);
    message.setGroup(group);

    when(chatMessageRepository.findById(901L)).thenReturn(Optional.of(message));
    when(groupMemberRepository.findAllByGroup(group))
        .thenReturn(List.of(senderMember, recipientMember));
    when(preferenceRepository.findByUserInAndSource(anyCollection(), eq("CHAT")))
        .thenReturn(List.of());
    when(notificationRepository
            .findByUserInAndSourceAndTypeAndReferenceIdAndReadAtIsNull(
                anyCollection(), eq("CHAT"), eq("NEW_MESSAGE"), eq("77")))
        .thenReturn(List.of(existing));

    service.enqueueChatMessage(901L);

    verify(notificationRepository, never()).saveAll(any());
  }

  private User user(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private NotificationPreference preference(User user, String source, boolean muted) {
    NotificationPreference preference = new NotificationPreference();
    preference.setUser(user);
    preference.setSource(source);
    preference.setMuted(muted);
    return preference;
  }
}
