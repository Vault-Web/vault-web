package vaultWeb.config.websocket;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.repositories.GroupMemberRepository;

@Component
@RequiredArgsConstructor
public class GroupSubscriptionInterceptor implements ChannelInterceptor {

  private static final String GROUP_PREFIX = "/topic/group";

  private static final Pattern GROUP_DESTINATION =
      Pattern.compile("^/topic/group/([1-9][0-9]*)(?:/[^/]+)*$");

  private final GroupMemberRepository groupMemberRepository;
  private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;

  private final AntPathMatcher pathMatcher = new AntPathMatcher();
  // Populated only from an authenticated, authorized inbound subscription.
  // Outbound broadcast headers can carry the sender, not the recipient.
  private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (accessor == null) {
      return message;
    }
    if (accessor.getCommand() == StompCommand.DISCONNECT) {
      forgetSession(accessor.getSessionId());
    }
    if (accessor.getCommand() == StompCommand.SEND
        && isGroupDestination(accessor.getDestination())) {
      // Only application handlers may publish validated group events.
      return reject(accessor, "Send group events through application endpoints");
    }
    if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
      return message;
    }

    String destination = accessor.getDestination();

    if (destination == null || destination.isBlank()) {
      return reject(accessor, "Subscription destination is required");
    }

    // A pattern such as /topic/** could otherwise match protected group topics.
    // Current application subscriptions all use concrete destinations.
    if (pathMatcher.isPattern(destination)) {
      return reject(accessor, "Wildcard subscriptions are not supported");
    }

    if (!isGroupDestination(destination)) {
      return message;
    }

    Long groupId = parseGroupId(destination);
    if (groupId == null) {
      return reject(accessor, "Invalid group subscription destination");
    }

    Principal principal = accessor.getUser();
    if (!(principal instanceof Authentication authentication)
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || authentication.getName() == null
        || authentication.getName().isBlank()) {
      return reject(accessor, "Authentication is required for group subscriptions");
    }

    String sessionId = accessor.getSessionId();
    if (sessionId == null || sessionId.isBlank()) {
      return reject(accessor, "Session is required for group subscriptions");
    }

    boolean member =
        groupMemberRepository.existsByGroupIdAndUserUsername(groupId, authentication.getName());

    if (!member) {
      return reject(accessor, "Not allowed to subscribe to this group");
    }

    String existingUser = sessionUsers.putIfAbsent(sessionId, authentication.getName());
    if (existingUser != null && !existingUser.equals(authentication.getName())) {
      return reject(accessor, "Session identity changed");
    }
    return message;
  }

  /** Checks current membership for the recipient immediately before socket delivery. */
  public Message<?> authorizeDelivery(Message<?> message) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
    String destination = headers.getDestination();
    if (headers.getMessageType() != SimpMessageType.MESSAGE || !isGroupDestination(destination)) {
      return message;
    }
    Long groupId = parseGroupId(destination);
    String sessionId = headers.getSessionId();
    String username = sessionId == null ? null : sessionUsers.get(sessionId);
    if (groupId == null || username == null) {
      return null;
    }
    try {
      return groupMemberRepository.existsByGroupIdAndUserUsername(groupId, username)
          ? message
          : null;
    } catch (DataAccessException exception) {
      // A failed lookup must never turn a group message into an authorized delivery.
      return null;
    }
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    forgetSession(event.getSessionId());
  }

  private void forgetSession(String sessionId) {
    if (sessionId != null) {
      sessionUsers.remove(sessionId);
    }
  }

  private boolean isGroupDestination(String destination) {
    return destination != null
        && (destination.equals(GROUP_PREFIX) || destination.startsWith(GROUP_PREFIX + "/"));
  }

  private Long parseGroupId(String destination) {
    Matcher matcher = GROUP_DESTINATION.matcher(destination);

    if (!matcher.matches()) {
      return null;
    }

    try {
      return Long.valueOf(matcher.group(1));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Message<?> reject(StompHeaderAccessor accessor, String reason) {
    String sessionId = accessor.getSessionId();

    if (sessionId != null && !sessionId.isBlank()) {
      SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
      headers.setSessionId(sessionId);
      headers.setLeaveMutable(true);

      String description =
          reason
              + " [subscription="
              + accessor.getSubscriptionId()
              + ", destination="
              + accessor.getDestination()
              + "]";

      messagingTemplate
          .getObject()
          .convertAndSendToUser(
              sessionId,
              "/queue/errors",
              new ChatErrorDto(description),
              headers.getMessageHeaders());
    }

    return null;
  }
}
