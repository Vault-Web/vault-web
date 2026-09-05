package vaultWeb.controllers;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import vaultWeb.dtos.ChatMessageDeletedDto;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.ChatMessage;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.services.ChatService;

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
  public void sendPrivateMessage(@Valid @Payload ChatMessageDto messageDto) {
    ChatMessage savedMessage = chatService.saveMessage(messageDto);

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
}
