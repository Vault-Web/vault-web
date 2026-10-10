package vaultWeb.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vaultWeb.dtos.NotificationDto;
import vaultWeb.dtos.NotificationPreferenceDto;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.GroupMember;
import vaultWeb.models.Notification;
import vaultWeb.models.NotificationPreference;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.NotificationPreferenceRepository;
import vaultWeb.repositories.NotificationRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.annotations.SecurityEventType;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
  private static final Set<String> SOURCES = Set.of("SECURITY", "CHAT");
  private static final Set<String> CRITICAL_SECURITY_TYPES =
      Set.of("PASSWORD_CHANGE", "NEW_DEVICE_DETECTED");

  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final UserRepository userRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final ChatMessageRepository chatMessageRepository;

  @Transactional(readOnly = true)
  public List<NotificationDto> list(String username, String source, boolean unreadOnly, int limit) {
    User user = currentUser(username);
    int safeLimit = Math.max(1, Math.min(limit, 100));
    if (source != null && !source.isBlank()) {
      String normalized = normalizeSource(source);
      return (unreadOnly
              ? notificationRepository.findByUserAndSourceAndReadAtIsNullOrderByCreatedAtDesc(
                  user, normalized, PageRequest.of(0, safeLimit))
              : notificationRepository.findByUserAndSourceOrderByCreatedAtDesc(
                  user, normalized, PageRequest.of(0, safeLimit)))
          .stream().map(NotificationDto::from).toList();
    }
    return (unreadOnly
            ? notificationRepository.findByUserAndReadAtIsNullOrderByCreatedAtDesc(
                user, PageRequest.of(0, safeLimit))
            : notificationRepository.findByUserOrderByCreatedAtDesc(
                user, PageRequest.of(0, safeLimit)))
        .stream().map(NotificationDto::from).toList();
  }

  @Transactional(readOnly = true)
  public long unreadCount(String username) {
    return notificationRepository.countByUserAndReadAtIsNull(currentUser(username));
  }

  @Transactional
  public NotificationDto markRead(String username, Long notificationId, boolean read) {
    User user = currentUser(username);
    Notification item =
        notificationRepository
            .findByIdAndUser(notificationId, user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    item.setReadAt(read ? Instant.now() : null);
    return NotificationDto.from(notificationRepository.save(item));
  }

  @Transactional
  public int markAllRead(String username) {
    return notificationRepository.markAllRead(currentUser(username), Instant.now());
  }

  @Transactional(readOnly = true)
  public List<NotificationPreferenceDto> preferences(String username) {
    User user = currentUser(username);
    return preferenceRepository.findByUserOrderBySourceAsc(user).stream()
        .map(p -> new NotificationPreferenceDto(p.getSource(), p.isMuted()))
        .toList();
  }

  @Transactional
  public NotificationPreferenceDto setMuted(String username, String source, boolean muted) {
    User user = currentUser(username);
    String normalized = normalizeSource(source);
    NotificationPreference preference =
        preferenceRepository
            .findByUserAndSource(user, normalized)
            .orElseGet(
                () -> {
                  NotificationPreference created = new NotificationPreference();
                  created.setUser(user);
                  created.setSource(normalized);
                  return created;
                });
    preference.setMuted(muted);
    preferenceRepository.save(preference);
    return new NotificationPreferenceDto(normalized, muted);
  }

  /** Creates a generic inbox item. It never accepts a chat message payload from callers. */
  @Transactional
  public void publish(
      User recipient,
      String source,
      String type,
      String title,
      String message,
      String linkUrl,
      String referenceId) {
    if (recipient == null) {
      return;
    }
    String normalized = normalizeSource(source);
    try {
      boolean criticalSecurityAlert =
          "SECURITY".equals(normalized) && CRITICAL_SECURITY_TYPES.contains(type);
      boolean muted =
          preferenceRepository
              .findByUserAndSource(recipient, normalized)
              .map(NotificationPreference::isMuted)
              .orElse(false);
      if (muted && !criticalSecurityAlert) {
        return;
      }

      Notification item = new Notification();
      item.setUser(recipient);
      item.setSource(normalized);
      item.setType(type);
      item.setTitle(title);
      item.setMessage(message);
      item.setLinkUrl(safeInternalLink(linkUrl) ? linkUrl : null);
      item.setReferenceId(referenceId);
      item.setCreatedAt(Instant.now());
      notificationRepository.save(item);
    } catch (Exception ex) {
      // Notification failure must not break a security audit or chat delivery.
      log.warn("Failed to persist notification for user id {}", recipient.getId(), ex);
    }
  }

  public void publishSecurityEvent(SecurityEvent event) {
    if (event == null || event.getUser() == null || !"SUCCESS".equals(event.getStatus())) {
      return;
    }
    SecurityEventType type = event.getEventType();
    if (type != SecurityEventType.NEW_DEVICE_DETECTED
        && type != SecurityEventType.PASSWORD_CHANGE
        && type != SecurityEventType.LOGIN) {
      return;
    }
    String title =
        type == SecurityEventType.NEW_DEVICE_DETECTED
            ? "New device detected"
            : type == SecurityEventType.PASSWORD_CHANGE ? "Password changed" : "New login";
    String message =
        type == SecurityEventType.NEW_DEVICE_DETECTED
            ? "A new device was registered on your account."
            : type == SecurityEventType.PASSWORD_CHANGE
                ? "Your account password was changed."
                : "A login to your account was recorded.";
    publish(
        event.getUser(),
        "SECURITY",
        type.name(),
        title,
        message,
        "/security-activity",
        String.valueOf(event.getId()));
  }

  /**
   * Enqueues chat fan-out by ID so the websocket send path doesn't query or write per recipient.
   * Loading the message in the async transaction also keeps lazy JPA relationships available.
   */
  @Async("notificationExecutor")
  @Transactional
  public void enqueueChatMessage(Long messageId) {
    if (messageId == null) {
      return;
    }
    try {
      chatMessageRepository
          .findById(messageId)
          .ifPresent(this::createChatNotifications);
    } catch (Exception ex) {
      // Async inbox work must never affect delivery of the underlying encrypted chat message.
      log.warn("Failed to create chat notifications for message id {}", messageId, ex);
    }
  }

  private void createChatNotifications(ChatMessage message) {
    if (message.getSender() == null || message.getSender().getId() == null) {
      return;
    }

    String title;
    String text;
    String referenceId;
    List<User> members;
    if (message.getPrivateChat() != null) {
      PrivateChat chat = message.getPrivateChat();
      title = "New private message";
      text = "You received a new encrypted message.";
      referenceId = String.valueOf(chat.getId());
      members =
          java.util.stream.Stream.of(chat.getUser1(), chat.getUser2())
              .filter(java.util.Objects::nonNull)
              .toList();
    } else if (message.getGroup() != null) {
      title = "New group message";
      String groupName = safeGroupName(message);
      text = "A new encrypted message was posted to " + groupName;
      referenceId = String.valueOf(message.getGroup().getId());
      members =
          groupMemberRepository.findAllByGroup(message.getGroup()).stream()
              .map(GroupMember::getUser)
              .filter(user -> user != null && user.getId() != null)
              .toList();
    } else {
      return;
    }

    Long senderId = message.getSender().getId();
    Map<Long, User> uniqueRecipients =
        members.stream()
            .filter(
                user -> user != null && user.getId() != null && !senderId.equals(user.getId()))
            .collect(
                Collectors.toMap(
                    User::getId,
                    user -> user,
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    List<User> recipients = new ArrayList<>(uniqueRecipients.values());
    if (recipients.isEmpty()) {
      return;
    }

    Set<Long> mutedUserIds =
        preferenceRepository.findByUserInAndSource(recipients, "CHAT").stream()
            .filter(NotificationPreference::isMuted)
            .map(preference -> preference.getUser().getId())
            .collect(Collectors.toSet());

    Set<Long> alreadyUnreadUserIds =
        notificationRepository
            .findByUserInAndSourceAndTypeAndReferenceIdAndReadAtIsNull(
                recipients, "CHAT", "NEW_MESSAGE", referenceId)
            .stream()
            .map(notification -> notification.getUser().getId())
            .collect(Collectors.toSet());

    List<Notification> newNotifications = new ArrayList<>();
    for (User recipient : recipients) {
      Long recipientId = recipient.getId();
      if (mutedUserIds.contains(recipientId) || alreadyUnreadUserIds.contains(recipientId)) {
        continue;
      }
      Notification item = new Notification();
      item.setUser(recipient);
      item.setSource("CHAT");
      item.setType("NEW_MESSAGE");
      item.setTitle(title);
      item.setMessage(text);
      item.setLinkUrl("/");
      item.setReferenceId(referenceId);
      item.setCreatedAt(Instant.now());
      newNotifications.add(item);
    }

    if (!newNotifications.isEmpty()) {
      notificationRepository.saveAll(newNotifications);
    }
  }

  private String safeGroupName(ChatMessage message) {
    String name = message.getGroup().getName();
    return name == null || name.isBlank()
        ? "a group"
        : name.substring(0, Math.min(name.length(), 100));
  }

  private boolean safeInternalLink(String link) {
    return link != null
        && link.startsWith("/")
        && !link.startsWith("//")
        && !link.contains("\\")
        && !link.contains("://");
  }

  private String normalizeSource(String source) {
    if (source == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source is required");
    }
    String normalized = source.trim().toUpperCase(Locale.ROOT);
    if (!SOURCES.contains(normalized)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unsupported notification source");
    }
    return normalized;
  }

  private User currentUser(String username) {
    return userRepository
        .findByUsername(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
