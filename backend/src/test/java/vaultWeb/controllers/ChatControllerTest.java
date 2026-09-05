package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import vaultWeb.dtos.ChatMessageDeletedDto;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.services.ChatService;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

  private static final String SENDER_DEVICE_ID = "device-1";
  private static final String E2EE_PAYLOAD = "{\"v\":2}";

  @Mock private SimpMessagingTemplate messagingTemplate;

  @Mock private ChatService chatService;

  @Mock private GroupMemberRepository groupMemberRepository;

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

  // --- deleteMessage ---

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

  private ChatMessageDto createGroupMessageRequest(Long groupId) {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setGroupId(groupId);
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
}
