package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vaultWeb.dtos.BatchOperationDto;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.ClearChatRequestDto;
import vaultWeb.dtos.CreateGroupFromChatsRequest;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.dtos.PrivateChatDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.DeviceRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.services.ChatService;
import vaultWeb.services.PrivateChatService;

@RestController
@RequestMapping("/api/private-chats")
@Tag(
    name = "Private Chat Controller",
    description =
        "Handles private chats between users, including chat creation and message retrieval")
@RequiredArgsConstructor
public class PrivateChatController {

  private final PrivateChatService privateChatService;
  private final ChatMessageRepository chatMessageRepository;
  private final DeviceRepository deviceRepository;
  private final PrivateChatRepository privateChatRepository;
  private final ChatService chatService;

  @GetMapping("/between")
  @Operation(
      summary = "Get or create a private chat between two users",
      description =
          """
                    This endpoint retrieves an existing private chat between two users, or creates a new one if it does not exist.
                    - 'sender' and 'receiver' are the usernames of the users.
                    - Returns a PrivateChatDto containing the chat ID and the usernames of both participants.
                    """)
  @ApiResponse(
      responseCode = "200",
      description = "Private chat created or retrieved successfully.")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public PrivateChatDto getOrCreatePrivateChat(
      @RequestParam String receiver, Authentication authentication) {
    if (authentication == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    String sender = authentication.getName();
    PrivateChat chat = privateChatService.getOrCreatePrivateChat(sender, receiver);

    return new PrivateChatDto(
        chat.getId(), chat.getUser1().getUsername(), chat.getUser2().getUsername());
  }

  @GetMapping("/private")
  @Operation(
      summary = "Get all messages of a private chat",
      description =
          """
                    Retrieves all messages from a specific private chat.
                    - 'privateChatId' is the ID of the private chat.
                    - Messages are ordered chronologically by timestamp.
                    - The message content is end-to-end encrypted and never decrypted by the server.
                    - Returns a list of ChatMessageDto containing encrypted payload, sender info, timestamp, and chat ID.
                    """)
  @ApiResponse(
      responseCode = "200",
      description = "Messages from private chat have been retrieved successfully.")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public List<ChatMessageDto> getPrivateChatMessages(
      @RequestParam Long privateChatId, Authentication authentication) {
    PrivateChat chat = getAuthorizedPrivateChat(privateChatId, authentication);
    List<ChatMessage> messages =
        chatMessageRepository.findByPrivateChatIdAndDeletedFalseOrderByTimestampAsc(chat.getId());

    return messages.stream()
        .map(
            message -> {
              ChatMessageDto dto = chatService.toDto(message);
              return dto;
            })
        .toList();
  }

  @GetMapping("/devices")
  @Operation(
      summary = "Get devices for private chat participants",
      description =
          "Retrieves all registered devices of users participating in the private chat. "
              + "The current user must be a participant.")
  public List<DeviceDto> getPrivateChatDevices(
      @RequestParam Long privateChatId, Authentication authentication) {
    PrivateChat chat = getAuthorizedPrivateChat(privateChatId, authentication);
    List<User> users =
        java.util.stream.Stream.of(chat.getUser1(), chat.getUser2())
            .filter(u -> u != null)
            .toList();
    return deviceRepository.findByUserIn(users).stream().map(DeviceDto::from).toList();
  }

  private PrivateChat getAuthorizedPrivateChat(Long privateChatId, Authentication authentication) {
    if (authentication == null) {
      throw new UnauthorizedException("User not authenticated");
    }

    PrivateChat chat =
        privateChatRepository
            .findById(privateChatId)
            .orElseThrow(() -> new PrivateChatNotFoundException("Private chat not found"));
    String username = authentication.getName();
    boolean isParticipant =
        (chat.getUser1() != null && username.equals(chat.getUser1().getUsername()))
            || (chat.getUser2() != null && username.equals(chat.getUser2().getUsername()));
    if (!isParticipant) {
      throw new AccessDeniedException("Not allowed to access this private chat");
    }
    return chat;
  }

  @GetMapping("/user-chats")
  @Operation(
      summary = "Get all private chats for the current user",
      description = "Retrieves all private chats where the current user is a participant")
  public List<PrivateChatDto> getUserChats(Authentication authentication) {
    String username = authentication.getName();
    List<PrivateChat> privateChats = privateChatService.getUserPrivateChats(username);

    List<PrivateChatDto> privateChatDtos =
        privateChats.stream()
            .map(
                chat ->
                    new PrivateChatDto(
                        chat.getId(), chat.getUser1().getUsername(), chat.getUser2().getUsername()))
            .toList();
    return privateChatDtos;
  }

  @PostMapping("/clear-multiple")
  @Operation(
      summary = "Clear message from Multiple Chats",
      description = "Delete all message from the selected private chats")
  @ApiResponse(responseCode = "200", description = "Message Cleared Successfully")
  @ApiResponse(responseCode = "401", description = "Unauthorized, user need to valid token")
  @ApiResponse(responseCode = "403", description = "User not authorized to clear those chats")
  public BatchOperationDto clearMultipleChats(
      @Valid @RequestBody ClearChatRequestDto request, Authentication authentication) {
    String currentUsername = authentication.getName();
    int deleteCount =
        privateChatService.clearMultipleChats(request.getPrivateChatIds(), currentUsername);
    return BatchOperationDto.builder()
        .success(true)
        .message("Successfully cleared multiple chats.")
        .affectedCount(deleteCount)
        .build();
  }

  @PostMapping("/create-group-from-chats")
  @Operation(
      summary = "Create a group from multiple private chats",
      description = "Combines all participants from selected private chats into a new group")
  public BatchOperationDto createGroupFromChats(
      @Valid @RequestBody CreateGroupFromChatsRequest groupCreationRequest,
      Authentication authentication) {
    String currentUsername = authentication.getName();
    Long groupId =
        privateChatService.createGroupFromChats(
            groupCreationRequest.getPrivateChatIds(),
            groupCreationRequest.getGroupName(),
            groupCreationRequest.getDescription(),
            currentUsername);
    return BatchOperationDto.builder()
        .success(true)
        .message("Successfully created group from chats.")
        .groupId(groupId)
        .build();
  }
}
