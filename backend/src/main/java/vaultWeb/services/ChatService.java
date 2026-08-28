package vaultWeb.services;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.models.enums.MessageType;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;

/**
 * Service responsible for handling chat-related operations.
 *
 * <p>This service provides methods to save chat messages for both group chats and private chats.
 * Messages are stored as end-to-end encrypted payloads for both chat types. The service supports
 * identifying the sender either by ID or username and can handle automatic timestamping if none is
 * provided.
 *
 * <p>Main responsibilities:
 *
 * <ul>
 *   <li>Validate sender existence by ID or username.
 *   <li>Associate messages with a group or private chat.
 *   <li>Persist chat messages into the database.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatMessageRepository chatMessageRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final PrivateChatRepository privateChatRepository;
  private final PollService pollService;

  /**
   * Saves a chat message to a group or private chat.
   *
   * <p>The sender is identified either by ID or username. If a timestamp is not provided, the
   * current time is used. The message must belong to either a group or a private chat. All chat
   * messages must provide an end-to-end encrypted payload.
   *
   * @param dto DTO containing the message content, sender information, timestamp, and either a
   *     groupId or privateChatId.
   * @return The persisted ChatMessage entity with an end-to-end payload.
   * @throws UserNotFoundException if the sender cannot be found by ID or username.
   * @throws GroupNotFoundException if neither groupId nor privateChatId is provided, or if the
   *     specified group/private chat does not exist.
   * @throws IllegalArgumentException if encrypted payload metadata is missing.
   */
  public ChatMessage saveMessage(ChatMessageDto dto) {
    User sender;

    if (dto.getSenderId() != null) {
      sender =
          userRepository
              .findById(dto.getSenderId())
              .orElseThrow(() -> new UserNotFoundException("Sender not found by ID"));
    } else if (dto.getSenderUsername() != null) {
      sender =
          userRepository
              .findByUsername(dto.getSenderUsername())
              .orElseThrow(() -> new UserNotFoundException("Sender not found by username"));
    } else {
      throw new UserNotFoundException("Sender information missing");
    }
    MessageType messageType =
        dto.getMessageType() != null ? dto.getMessageType() : MessageType.TEXT;
    dto.setMessageType(messageType);

    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setMessageType(messageType);
    message.setClientMessageId(dto.getClientMessageId());

    if (dto.getTimestamp() != null) {
      message.setTimestamp(Instant.parse(dto.getTimestamp()));
    } else {
      message.setTimestamp(Instant.now());
    }

    if (messageType == MessageType.TEXT) {
      if (dto.getE2eePayload() == null
          || dto.getE2eePayload().isBlank()
          || dto.getSenderDeviceId() == null
          || dto.getSenderDeviceId().isBlank()) {
        throw new IllegalArgumentException(
            "Missing end-to-end encrypted payload or sender device ID");
      }
    }

    if (dto.getGroupId() != null) {
      Group group =
          groupRepository
              .findById(dto.getGroupId())
              .orElseThrow(
                  () ->
                      new GroupNotFoundException(
                          "Group with id " + dto.getGroupId() + " not found"));
      message.setE2eePayload(dto.getE2eePayload());
      message.setSenderDeviceId(dto.getSenderDeviceId());
      message.setGroup(group);
    } else if (dto.getPrivateChatId() != null) {
      PrivateChat privateChat =
          privateChatRepository
              .findById(dto.getPrivateChatId())
              .orElseThrow(() -> new PrivateChatNotFoundException("Private chat not found"));

      message.setE2eePayload(dto.getE2eePayload());
      message.setSenderDeviceId(dto.getSenderDeviceId());
      message.setPrivateChat(privateChat);
    } else {
      throw new GroupNotFoundException("Either groupId or privateChatId must be provided");
    }

    return chatMessageRepository.save(message);
  }

  public ChatMessageDto toDto(ChatMessage message) {

    ChatMessageDto dto = new ChatMessageDto();

    dto.setTimestamp(message.getTimestamp().toString());

    dto.setSenderId(message.getSender().getId());

    dto.setSenderUsername(message.getSender().getUsername());

    dto.setSenderDeviceId(message.getSenderDeviceId());

    dto.setClientMessageId(message.getClientMessageId());

    MessageType messageType =
        message.getMessageType() != null ? message.getMessageType() : MessageType.TEXT;
    dto.setMessageType(messageType);

    if (message.getGroup() != null) {

      dto.setGroupId(message.getGroup().getId());
    }

    if (message.getPrivateChat() != null) {

      dto.setPrivateChatId(message.getPrivateChat().getId());
    }

    if (messageType == MessageType.TEXT) {

      dto.setE2eePayload(message.getE2eePayload());

    } else if (messageType == MessageType.POLL) {
      if (message.getPoll() != null) {
        dto.setPoll(pollService.toResponseDto(message.getPoll()));
      }
    }

    return dto;
  }
}
