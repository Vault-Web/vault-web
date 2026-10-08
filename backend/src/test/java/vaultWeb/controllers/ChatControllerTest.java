package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityNotFoundException;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.dtos.ChatMessageDeletedDto;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.MessageStatusUpdateDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.models.enums.MessageStatus;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.services.ChatService;
import vaultWeb.services.PrivateChatService;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

  private static final String SENDER_DEVICE_ID = "device-1";
  private static final String E2EE_PAYLOAD = "{\"v\":2}";

  @Mock private SimpMessagingTemplate messagingTemplate;

  @Mock private ChatService chatService;

  @Mock private GroupMemberRepository groupMemberRepository;

  @Mock private PrivateChatRepository privateChatRepository;

  @Mock private PrivateChatService privateChatService;

  @InjectMocks private ChatController chatController;

  @Test
  void shouldSendGroupMessage_WhenAuthenticatedUserIsGroupMember() {
    ChatMessageDto request = createGroupMessageRequest(10L);
    Principal principal = () -> "alice";
    ChatMessage savedMessage = createSavedGroupMessage(10L, "alice");
    ChatMessageDto response = createGroupMessageRequest(10L);
    response.setSenderUsername("alice");

    when(groupMemberRepository.existsByGroupIdAndUserUsername(10L, "alice")).thenReturn(true);
    when(chatService.saveMessage(any(ChatMessageDto.class))).thenReturn(savedMessage);
    when(chatService.toDto(savedMessage)).thenReturn(response);

    chatController.sendMessage(request, principal);

    ArgumentCaptor<ChatMessageDto> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
    verify(chatService).saveMessage(dtoCaptor.capture());
    assertEquals("alice", dtoCaptor.getValue().getSenderUsername());
    verify(messagingTemplate).convertAndSend(eq("/topic/group/10"), any(ChatMessageDto.class));
  }

  @Test
  void shouldRejectGroupMessage_WhenAuthenticatedUserIsNotGroupMember() {
    ChatMessageDto request = createGroupMessageRequest(10L);
    Principal principal = () -> "mallory";

    when(groupMemberRepository.existsByGroupIdAndUserUsername(10L, "mallory")).thenReturn(false);

    assertThrows(AccessDeniedException.class, () -> chatController.sendMessage(request, principal));
    verify(chatService, never()).saveMessage(any());
    verify(messagingTemplate, never()).convertAndSend(any(String.class), any(ChatMessageDto.class));
  }

  @Test
  void shouldRejectGroupMessage_WhenUnauthenticated() {
    ChatMessageDto request = createGroupMessageRequest(10L);

    assertThrows(UnauthorizedException.class, () -> chatController.sendMessage(request, null));
    verify(groupMemberRepository, never()).existsByGroupIdAndUserUsername(any(), any());
    verify(chatService, never()).saveMessage(any());
    verify(messagingTemplate, never()).convertAndSend(any(String.class), any(ChatMessageDto.class));
  }

  @Test
  void shouldSendPrivateMessage_WhenAuthenticatedUserIsParticipant() {
    ChatMessageDto request = createPrivateMessageRequest(20L);
    Principal principal = () -> "alice";
    ChatMessage savedMessage = createSavedPrivateMessage(20L, "alice", "bob");
    ChatMessageDto response = createPrivateMessageRequest(20L);
    response.setSenderUsername("alice");

    when(privateChatRepository.existsByIdAndParticipantUsername(20L, "alice")).thenReturn(true);
    when(chatService.saveMessage(any(ChatMessageDto.class))).thenReturn(savedMessage);
    when(chatService.toDto(savedMessage)).thenReturn(response);

    chatController.sendPrivateMessage(request, principal);

    ArgumentCaptor<ChatMessageDto> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
    verify(chatService).saveMessage(dtoCaptor.capture());
    assertEquals("alice", dtoCaptor.getValue().getSenderUsername());
    verify(messagingTemplate)
        .convertAndSendToUser(eq("alice"), eq("/queue/private"), any(ChatMessageDto.class));
    verify(messagingTemplate)
        .convertAndSendToUser(eq("bob"), eq("/queue/private"), any(ChatMessageDto.class));
  }

  @Test
  void shouldRejectPrivateMessage_WhenAuthenticatedUserIsNotParticipant() {
    // Mallory is not a participant of private chat 20 (alice/bob), and attempts to
    // inject a message while spoofing "alice" as the sender in the payload.
    ChatMessageDto request = createPrivateMessageRequest(20L);
    Principal principal = () -> "mallory";

    when(privateChatRepository.existsByIdAndParticipantUsername(20L, "mallory")).thenReturn(false);

    assertThrows(
        AccessDeniedException.class, () -> chatController.sendPrivateMessage(request, principal));
    verify(chatService, never()).saveMessage(any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(any(String.class), any(String.class), any(ChatMessageDto.class));
  }

  @Test
  void shouldRejectPrivateMessage_WhenUnauthenticated() {
    ChatMessageDto request = createPrivateMessageRequest(20L);

    assertThrows(
        UnauthorizedException.class, () -> chatController.sendPrivateMessage(request, null));
    verify(privateChatRepository, never()).existsByIdAndParticipantUsername(any(), any());
    verify(chatService, never()).saveMessage(any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(any(String.class), any(String.class), any(ChatMessageDto.class));
  }

  @Test
  void shouldOverwriteSenderUsername_WithPrincipalName_NotSpoofedPayloadValue() {
    // request carries a spoofed sender ("spoofed-sender" from the helper); confirm the
    // dto passed to saveMessage carries the authenticated principal's name instead.
    ChatMessageDto request = createPrivateMessageRequest(20L);
    Principal principal = () -> "alice";
    ChatMessage savedMessage = createSavedPrivateMessage(20L, "alice", "bob");
    ChatMessageDto response = createPrivateMessageRequest(20L);
    response.setSenderUsername("alice");

    when(privateChatRepository.existsByIdAndParticipantUsername(20L, "alice")).thenReturn(true);
    when(chatService.saveMessage(any(ChatMessageDto.class))).thenReturn(savedMessage);
    when(chatService.toDto(savedMessage)).thenReturn(response);

    chatController.sendPrivateMessage(request, principal);

    ArgumentCaptor<ChatMessageDto> dtoCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
    verify(chatService).saveMessage(dtoCaptor.capture());
    assertEquals("alice", dtoCaptor.getValue().getSenderUsername());
    assertEquals(null, dtoCaptor.getValue().getSenderId());
    // --- deleteMessage ---
  }

  @Test
  void shouldBroadcastGroupDelete_toDedicatedDeletedTopic_notNormalMessageTopic() {
    Principal principal = () -> "alice";
    ChatMessage deletedMessage = createSavedGroupMessage(20L, "alice");
    deletedMessage.setClientMessageId("client-uuid-1");

    when(chatService.deleteMessage("client-uuid-1", "alice")).thenReturn(deletedMessage);

    chatController.deleteMessage("client-uuid-1", principal);

    // Regression test: delete events must go to their own destination, since
    // reusing the normal message topic caused every incoming normal message
    // (which also carries a clientMessageId) to be misread as a deletion.
    verify(messagingTemplate)
        .convertAndSend(eq("/topic/group/20/deleted"), any(ChatMessageDeletedDto.class));
    verify(messagingTemplate, never())
        .convertAndSend(eq("/topic/group/20"), any(ChatMessageDeletedDto.class));
    verify(messagingTemplate, never())
        .convertAndSendToUser(anyString(), anyString(), any(ChatMessageDeletedDto.class));
  }

  @Test
  void shouldBroadcastPrivateDelete_toDedicatedDeletedQueue_notNormalMessageQueue() {
    Principal principal = () -> "alice";
    ChatMessage deletedMessage = createSavedPrivateMessage("alice", "bob");
    deletedMessage.setClientMessageId("client-uuid-2");

    when(chatService.deleteMessage("client-uuid-2", "alice")).thenReturn(deletedMessage);

    chatController.deleteMessage("client-uuid-2", principal);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq("alice"), eq("/queue/private/deleted"), any(ChatMessageDeletedDto.class));
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq("bob"), eq("/queue/private/deleted"), any(ChatMessageDeletedDto.class));
    verify(messagingTemplate, never())
        .convertAndSendToUser(anyString(), eq("/queue/private"), any(ChatMessageDeletedDto.class));
    verify(messagingTemplate, never())
        .convertAndSend(anyString(), any(ChatMessageDeletedDto.class));
  }

  @Test
  void shouldPassSenderUsername_toChatServiceDeleteMessage() {
    Principal principal = () -> "alice";
    ChatMessage deletedMessage = createSavedGroupMessage(20L, "alice");

    when(chatService.deleteMessage("client-uuid-3", "alice")).thenReturn(deletedMessage);

    chatController.deleteMessage("client-uuid-3", principal);

    verify(chatService).deleteMessage("client-uuid-3", "alice");
  }

  @Test
  void shouldRejectDelete_WhenUnauthenticated() {
    assertThrows(
        UnauthorizedException.class, () -> chatController.deleteMessage("client-uuid-4", null));
    verify(chatService, never()).deleteMessage(any(), any());
    verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
  }

  @Test
  void shouldSendReadReceipt_toOtherParticipant() {
    Principal principal = () -> "bob";
    when(privateChatService.markChatAsRead(5L, "bob")).thenReturn("alice");

    chatController.markPrivateChatAsRead(5L, principal);

    ArgumentCaptor<MessageStatusUpdateDto> captor =
        ArgumentCaptor.forClass(MessageStatusUpdateDto.class);
    verify(messagingTemplate)
        .convertAndSendToUser(eq("alice"), eq("/queue/private/status"), captor.capture());
    assertEquals(5L, captor.getValue().getPrivateChatId());
    assertEquals(MessageStatus.READ, captor.getValue().getStatus());
  }

  @Test
  void shouldNotSendReadReceipt_WhenUserIsNotParticipant() {
    Principal principal = () -> "mallory";
    when(privateChatService.markChatAsRead(5L, "mallory"))
        .thenThrow(new AccessDeniedException("not a participant"));

    assertThrows(
        AccessDeniedException.class, () -> chatController.markPrivateChatAsRead(5L, principal));
    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
  }

  @Test
  void shouldRejectRead_WhenUnauthenticated() {
    assertThrows(UnauthorizedException.class, () -> chatController.markPrivateChatAsRead(5L, null));
    verify(privateChatService, never()).markChatAsRead(any(), any());
  }

  @Test
  void shouldSendDeliveredReceipt_toSender() {
    Principal principal = () -> "bob";
    ChatMessage message = createSavedPrivateMessage(5L, "alice", "bob");
    message.setClientMessageId("client-uuid-1");
    message.setStatus(MessageStatus.DELIVERED);
    message.setDeliveredAt(java.time.Instant.parse("2026-03-26T10:16:00Z"));
    when(chatService.markMessageDelivered("client-uuid-1", "bob")).thenReturn(message);

    chatController.markPrivateMessageDelivered("client-uuid-1", principal);

    ArgumentCaptor<MessageStatusUpdateDto> captor =
        ArgumentCaptor.forClass(MessageStatusUpdateDto.class);
    verify(messagingTemplate)
        .convertAndSendToUser(eq("alice"), eq("/queue/private/status"), captor.capture());
    assertEquals(5L, captor.getValue().getPrivateChatId());
    assertEquals("client-uuid-1", captor.getValue().getClientMessageId());
    assertEquals(MessageStatus.DELIVERED, captor.getValue().getStatus());
    assertEquals("2026-03-26T10:16:00Z", captor.getValue().getTimestamp());
  }

  @Test
  void shouldNotSendDeliveredReceipt_WhenUserIsNotRecipient() {
    Principal principal = () -> "alice";
    when(chatService.markMessageDelivered("client-uuid-1", "alice"))
        .thenThrow(new AccessDeniedException("Only the recipient can confirm delivery"));

    assertThrows(
        AccessDeniedException.class,
        () -> chatController.markPrivateMessageDelivered("client-uuid-1", principal));
    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
  }

  @Test
  void shouldRejectDelivered_WhenUnauthenticated() {
    assertThrows(
        UnauthorizedException.class,
        () -> chatController.markPrivateMessageDelivered("client-uuid-1", null));
    verify(chatService, never()).markMessageDelivered(any(), any());
  }

  private ChatMessageDto createGroupMessageRequest(Long groupId) {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setGroupId(groupId);
    dto.setSenderUsername("spoofed-sender");
    dto.setSenderDeviceId(SENDER_DEVICE_ID);
    dto.setE2eePayload(E2EE_PAYLOAD);
    return dto;
  }

  private ChatMessageDto createPrivateMessageRequest(Long privateChatId) {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setPrivateChatId(privateChatId);
    dto.setSenderUsername("spoofed-sender");
    dto.setSenderDeviceId(SENDER_DEVICE_ID);
    dto.setE2eePayload(E2EE_PAYLOAD);
    return dto;
  }

  private ChatMessage createSavedGroupMessage(Long groupId, String username) {
    User sender = new User();
    sender.setUsername(username);
    Group group = new Group();
    group.setId(groupId);
    ChatMessage message = new ChatMessage();
    message.setGroup(group);
    message.setSender(sender);
    message.setSenderDeviceId(SENDER_DEVICE_ID);
    message.setE2eePayload(E2EE_PAYLOAD);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    return message;
  }

  private ChatMessage createSavedPrivateMessage(
      Long privateChatId, String user1Username, String user2Username) {
    User user1 = new User();
    user1.setUsername(user1Username);
    User user2 = new User();
    user2.setUsername(user2Username);
    PrivateChat privateChat = new PrivateChat();
    privateChat.setId(privateChatId);
    privateChat.setUser1(user1);
    privateChat.setUser2(user2);

    User sender = new User();
    sender.setUsername(user1Username);

    ChatMessage message = new ChatMessage();
    message.setPrivateChat(privateChat);
    message.setSender(sender);
    message.setSenderDeviceId(SENDER_DEVICE_ID);
    message.setE2eePayload(E2EE_PAYLOAD);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    return message;
  }

  private ChatMessage createSavedPrivateMessage(String username1, String username2) {
    User sender = new User();
    sender.setUsername(username1);
    User user1 = new User();
    user1.setUsername(username1);
    User user2 = new User();
    user2.setUsername(username2);
    PrivateChat privateChat = new PrivateChat();
    privateChat.setUser1(user1);
    privateChat.setUser2(user2);
    ChatMessage message = new ChatMessage();
    message.setPrivateChat(privateChat);
    message.setSender(sender);
    message.setSenderDeviceId(SENDER_DEVICE_ID);
    message.setE2eePayload(E2EE_PAYLOAD);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    return message;
  }

  // --- handleChatException ---

  @Test
  void shouldReportEntityNotFound_asChatErrorDto() {
    Principal principal = () -> "alice";
    EntityNotFoundException ex = new EntityNotFoundException("Chat message not found");

    ChatErrorDto result = chatController.handleChatException(ex, principal);

    assertEquals("Chat message not found", result.getError());
  }

  @Test
  void shouldReportAccessDenied_asChatErrorDto() {
    Principal principal = () -> "mallory";
    AccessDeniedException ex = new AccessDeniedException("You can only delete your own messages");

    ChatErrorDto result = chatController.handleChatException(ex, principal);

    assertEquals("You can only delete your own messages", result.getError());
  }

  @Test
  void shouldReportUnauthorized_asChatErrorDto_evenWithNullPrincipal() {
    UnauthorizedException ex = new UnauthorizedException("User not authenticated");

    // principal is null here by construction: this is exactly the case where
    // UnauthorizedException is thrown in this controller. The handler must not
    // throw itself (e.g. a NullPointerException on principal.getName()) just
    // because there's no authenticated session to log a username for.
    ChatErrorDto result = chatController.handleChatException(ex, null);

    assertEquals("User not authenticated", result.getError());
  }

  @Test
  void shouldReportIllegalArgument_asChatErrorDto() {
    Principal principal = () -> "alice";
    IllegalArgumentException ex =
        new IllegalArgumentException("Group ID is required for group messages");

    ChatErrorDto result = chatController.handleChatException(ex, principal);

    assertEquals("Group ID is required for group messages", result.getError());
  }
}
