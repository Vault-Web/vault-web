package vaultWeb.controllers;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.dtos.ChatMessageDeletedDto;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.MessageStatusUpdateDto;
import vaultWeb.exceptions.PrivateMessageSendException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.enums.MessageStatus;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.services.ChatService;
import vaultWeb.services.PrivateChatService;

/**
 * Controller responsible for handling WebSocket-based chat functionality.
 *
 * <p>Supports both group chat and private messages. Messages are first persisted via ChatService
 * and then dispatched to the corresponding topics or users.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

  private final SimpMessagingTemplate messagingTemplate;
  private final ChatService chatService;
  private final GroupMemberRepository groupMemberRepository;
  private final PrivateChatRepository privateChatRepository;
  private final PrivateChatService privateChatService;

  /**
   * Handles incoming group chat messages from clients and broadcasts them to all subscribers of the
   * specified group topic.
   *
   * @param messageDto DTO containing message content, sender information, and target group
   */
  @MessageMapping("/chat.send")
  public void sendMessage(@Valid @Payload ChatMessageDto messageDto, Principal principal) {
    authorizeGroupMessage(messageDto, principal);
    ChatMessage savedMessage = chatService.saveMessage(messageDto);

    ChatMessageDto responseDto = chatService.toDto(savedMessage);

    messagingTemplate.convertAndSend(
        "/topic/group/" + savedMessage.getGroup().getId(), responseDto);
  }

  private void authorizeGroupMessage(ChatMessageDto messageDto, Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new UnauthorizedException("User not authenticated");
    }
    if (messageDto.getGroupId() == null) {
      throw new IllegalArgumentException("Group ID is required for group messages");
    }

    String username = principal.getName();
    boolean isMember =
        groupMemberRepository.existsByGroupIdAndUserUsername(messageDto.getGroupId(), username);
    if (!isMember) {
      throw new AccessDeniedException("Not allowed to send messages to this group");
    }

    messageDto.setSenderId(null);
    messageDto.setSenderUsername(username);
  }

  /**
   * Handles incoming private chat messages from clients and sends them to both users of the private
   * chat. The message content is end-to-end encrypted and never decrypted by the server.
   *
   * @param messageDto DTO containing message content, sender information, and private chat ID
   */
  @MessageMapping("/chat.private.send")
  public void sendPrivateMessage(@Valid @Payload ChatMessageDto messageDto, Principal principal) {
    ChatMessage savedMessage;
    try {
      authorizePrivateMessage(messageDto, principal);
      savedMessage = chatService.saveMessage(messageDto);
    } catch (EntityNotFoundException
        | AccessDeniedException
        | UnauthorizedException
        | IllegalArgumentException
        | PrivateChatNotFoundException
        | UserNotFoundException ex) {
      throw new PrivateMessageSendException(messageDto.getClientMessageId(), ex);
    }

    ChatMessageDto responseDto = chatService.toDto(savedMessage);

    String user1 = savedMessage.getPrivateChat().getUser1().getUsername();
    String user2 = savedMessage.getPrivateChat().getUser2().getUsername();

    Set<String> recipients = new LinkedHashSet<>();
    recipients.add(user1);
    recipients.add(user2);

    recipients.forEach(
        user -> messagingTemplate.convertAndSendToUser(user, "/queue/private", responseDto));

    log.debug(
        "Private message sent from {} to privateChat {}",
        responseDto.getSenderUsername(),
        responseDto.getPrivateChatId());
  }

  private void authorizePrivateMessage(ChatMessageDto messageDto, Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new UnauthorizedException("User not authenticated");
    }
    if (messageDto.getPrivateChatId() == null) {
      throw new IllegalArgumentException("Private chat ID is required for private messages");
    }

    String username = principal.getName();
    boolean isParticipant =
        privateChatRepository.existsByIdAndParticipantUsername(
            messageDto.getPrivateChatId(), username);
    if (!isParticipant) {
      throw new AccessDeniedException("Not allowed to send messages to this private chat");
    }

    messageDto.setSenderId(null);
    messageDto.setSenderUsername(username);
  }

  @MessageMapping("/chat.delete")
  public void deleteMessage(@Payload String clientMessageId, Principal principal) {

    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new UnauthorizedException("User not authenticated");
    }

    ChatMessage deletedMessage = chatService.deleteMessage(clientMessageId, principal.getName());

    ChatMessageDeletedDto responseDto =
        new ChatMessageDeletedDto(deletedMessage.getClientMessageId());

    if (deletedMessage.getGroup() != null) {
      messagingTemplate.convertAndSend(
          "/topic/group/" + deletedMessage.getGroup().getId() + "/deleted", responseDto);
    } else if (deletedMessage.getPrivateChat() != null) {
      String user1 = deletedMessage.getPrivateChat().getUser1().getUsername();
      String user2 = deletedMessage.getPrivateChat().getUser2().getUsername();

      Set<String> recipients = new LinkedHashSet<>();
      recipients.add(user1);
      recipients.add(user2);

      recipients.forEach(
          user ->
              messagingTemplate.convertAndSendToUser(user, "/queue/private/deleted", responseDto));
    }
  }

  /**
   * Handles a delivery receipt from the recipient of a private message and notifies the sender on
   * {@code /user/queue/private/status}. A message that is already READ is never downgraded.
   *
   * @param clientMessageId client-generated ID of the delivered message
   */
  @MessageMapping("/chat.private.delivered")
  public void markPrivateMessageDelivered(@Payload String clientMessageId, Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new UnauthorizedException("User not authenticated");
    }

    ChatMessage message = chatService.markMessageDelivered(clientMessageId, principal.getName());

    messagingTemplate.convertAndSendToUser(
        message.getSender().getUsername(),
        "/queue/private/status",
        new MessageStatusUpdateDto(
            message.getPrivateChat().getId(),
            message.getClientMessageId(),
            message.getStatus(),
            message.getDeliveredAt().toString()));
  }

  /**
   * Marks all messages from the other participant in a private chat as READ and notifies them on
   * {@code /user/queue/private/status}. The update carries no clientMessageId because it applies to
   * the whole chat.
   *
   * @param privateChatId ID of the private chat the user opened
   */
  @MessageMapping("/chat.private.read")
  public void markPrivateChatAsRead(@Payload Long privateChatId, Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new UnauthorizedException("User not authenticated");
    }

    String otherUser = privateChatService.markChatAsRead(privateChatId, principal.getName());

    messagingTemplate.convertAndSendToUser(
        otherUser,
        "/queue/private/status",
        new MessageStatusUpdateDto(
            privateChatId, null, MessageStatus.READ, Instant.now().toString()));
  }

  /**
   * Reports failures from STOMP handlers in this controller back to the client.
   *
   * <p>Unlike {@code @RestController} endpoints, {@code @MessageMapping} methods have no automatic
   * exception-to-response translation: an uncaught exception here is only logged server-side and
   * the caller's socket receives nothing, leaving the client's UI in a stale state (e.g. a message
   * the user tried to delete silently stays visible after an already-deleted or not-the-sender
   * failure). This handler catches the failure modes {@link #deleteMessage}, {@link #sendMessage}
   * and the status receipt handlers can throw and relays them to the user's private error queue.
   */
  @MessageExceptionHandler({
    EntityNotFoundException.class,
    AccessDeniedException.class,
    UnauthorizedException.class,
    IllegalArgumentException.class,
    PrivateChatNotFoundException.class,
    UserNotFoundException.class,
    PrivateMessageSendException.class
  })
  @SendToUser("/queue/errors")
  public ChatErrorDto handleChatException(Exception ex, Principal principal) {
    log.warn(
        "Chat operation failed for user {}: {}",
        principal != null ? principal.getName() : "unauthenticated",
        ex.getMessage());
    String clientMessageId =
        ex instanceof PrivateMessageSendException sendEx ? sendEx.getClientMessageId() : null;
    return new ChatErrorDto(ex.getMessage(), clientMessageId);
  }
}
