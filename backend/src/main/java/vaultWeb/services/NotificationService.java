package vaultWeb.services;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.NotificationPreferenceRepository;
import vaultWeb.repositories.NotificationRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.annotations.SecurityEventType;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
  private static final Set<String> SOURCES = Set.of("SECURITY", "CHAT");
  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final UserRepository userRepository;
  private final PrivateChatRepository privateChatRepository;
  private final GroupMemberRepository groupMemberRepository;

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
            : notificationRepository.findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, safeLimit)))
        .stream().map(NotificationDto::from).toList();
  }

  @Transactional(readOnly = true)
  public long unreadCount(String username) {
    return notificationRepository.countByUserAndReadAtIsNull(currentUser(username));
  }

  @Transactional
  public NotificationDto markRead(String username, Long notificationId, boolean read) {
    User user = currentUser(username);
    Notification item = notificationRepository.findByIdAndUser(notificationId, user)
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
        .map(p -> new NotificationPreferenceDto(p.getSource(), p.isMuted())).toList();
  }

  @Transactional
  public NotificationPreferenceDto setMuted(String username, String source, boolean muted) {
    User user = currentUser(username);
    String normalized = normalizeSource(source);
    NotificationPreference preference = preferenceRepository.findByUserAndSource(user, normalized)
        .orElseGet(() -> {
          NotificationPreference created = new NotificationPreference();
          created.setUser(user);
          created.setSource(normalized);
          return created;
        });
    preference.setMuted(muted);
    preferenceRepository.save(preference);
    return new NotificationPreferenceDto(normalized, muted);
  }

  /** Creates a generic inbox item. It never accepts a message payload from callers. */
  @Transactional
  public void publish(User recipient, String source, String type, String title, String message,
      String linkUrl, String referenceId) {
    if (recipient == null) return;
    String normalized = normalizeSource(source);
    try {
      if (preferenceRepository.findByUserAndSource(recipient, normalized)
          .map(NotificationPreference::isMuted).orElse(false)) return;
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
    if (event == null || event.getUser() == null || !"SUCCESS".equals(event.getStatus())) return;
    SecurityEventType type = event.getEventType();
    if (type != SecurityEventType.NEW_DEVICE_DETECTED
        && type != SecurityEventType.PASSWORD_CHANGE
        && type != SecurityEventType.LOGIN) return;
    String title = type == SecurityEventType.NEW_DEVICE_DETECTED ? "New device detected"
        : type == SecurityEventType.PASSWORD_CHANGE ? "Password changed" : "New login";
    String msg = type == SecurityEventType.NEW_DEVICE_DETECTED
        ? "A new device was registered on your account."
        : type == SecurityEventType.PASSWORD_CHANGE ? "Your account password was changed."
        : "A login to your account was recorded.";
    publish(event.getUser(), "SECURITY", type.name(), title, msg, "/security-activity",
        String.valueOf(event.getId()));
  }

  public void publishChatMessage(ChatMessage message) {
    try {
      if (message == null || message.getSender() == null) return;
      String title;
      String text;
      String link;
      List<User> recipients;
      if (message.getPrivateChat() != null) {
        PrivateChat chat = message.getPrivateChat();
        title = "New private message";
        text = "You received a new encrypted message.";
        link = "/"; // No payload or message body is exposed in the inbox.
        recipients = List.of(chat.getUser1(), chat.getUser2());
      } else if (message.getGroup() != null) {
        title = "New group message";
        text = "A new encrypted message was posted to " + safeGroupName(message);
        link = "/";
        recipients = groupMemberRepository.findAllByGroup(message.getGroup()).stream()
            .map(GroupMember::getUser).filter(java.util.Objects::nonNull).toList();
      } else {
        return;
      }
      for (User recipient : recipients) {
        if (recipient != null && !recipient.getId().equals(message.getSender().getId())) {
          publish(recipient, "CHAT", "NEW_MESSAGE", title, text, link,
              String.valueOf(message.getId()));
        }
      }
    } catch (Exception ex) {
      log.warn("Failed to create chat notifications for message id {}",
          message == null ? null : message.getId(), ex);
    }
  }

  private String safeGroupName(ChatMessage message) {
    String name = message.getGroup().getName();
    return name == null || name.isBlank() ? "a group" : name.substring(0, Math.min(name.length(), 100));
  }

  private boolean safeInternalLink(String link) {
    return link != null && link.startsWith("/") && !link.startsWith("//")
        && !link.contains("\\") && !link.contains("://");
  }

  private String normalizeSource(String source) {
    if (source == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source is required");
    String normalized = source.trim().toUpperCase();
    if (!SOURCES.contains(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported notification source");
    }
    return normalized;
  }

  private User currentUser(String username) {
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
