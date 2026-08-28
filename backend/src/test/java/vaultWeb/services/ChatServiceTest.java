package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.PollResponseDto;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.Poll;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.models.enums.MessageType;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
  private static final String SENDER_DEVICE_ID = "dev-1";
  private static final String VALID_E2EE_PAYLOAD =
      "{\"v\":1,\"senderDeviceId\":\"dev-1\",\"senderPublicKey\":{\"kty\":\"EC\",\"crv\":\"P-256\","
          + "\"x\":\"abc\",\"y\":\"def\"},\"recipients\":{\"dev-2\":{\"iv\":\"aXY=\","
          + "\"salt\":\"c2FsdA==\",\"ciphertext\":\"Y2lwaGVy\"}}}";

  @Mock private ChatMessageRepository chatMessageRepository;

  @Mock private UserRepository userRepository;

  @Mock private GroupRepository groupRepository;

  @Mock private PrivateChatRepository privateChatRepository;

  @Mock private PollService pollService;

  @InjectMocks private ChatService chatService;

  private User createUser(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private Group createGroup(Long id) {
    Group group = new Group();
    group.setId(id);
    return group;
  }

  private PrivateChat createPrivateChat(Long id) {
    PrivateChat chat = new PrivateChat();
    chat.setId(id);
    return chat;
  }

  @Test
  void shouldSaveGroupMessageSuccessfully() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertNotNull(result);
    assertEquals(VALID_E2EE_PAYLOAD, result.getE2eePayload());
    assertEquals(SENDER_DEVICE_ID, result.getSenderDeviceId());
    assertEquals(sender, result.getSender());
    assertEquals(group, result.getGroup());
    assertEquals(MessageType.TEXT, result.getMessageType());
    verify(chatMessageRepository).save(any(ChatMessage.class));
  }

  @Test
  void shouldSavePrivateChatMessageSuccessfully() {
    User sender = createUser(1L, "user1");
    PrivateChat privateChat = createPrivateChat(5L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderUsername("user1");
    dto.setPrivateChatId(5L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findByUsername("user1")).thenReturn(Optional.of(sender));
    when(privateChatRepository.findById(5L)).thenReturn(Optional.of(privateChat));
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertNotNull(result);
    assertEquals(privateChat, result.getPrivateChat());
    assertEquals(VALID_E2EE_PAYLOAD, result.getE2eePayload());
    assertEquals(SENDER_DEVICE_ID, result.getSenderDeviceId());
    assertEquals(MessageType.TEXT, result.getMessageType());
    verify(chatMessageRepository).save(any(ChatMessage.class));
  }

  @Test
  void shouldMapMessageWithoutTypeAsText() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setGroup(group);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    message.setE2eePayload(VALID_E2EE_PAYLOAD);
    message.setSenderDeviceId(SENDER_DEVICE_ID);

    ChatMessageDto dto = chatService.toDto(message);

    assertEquals(MessageType.TEXT, dto.getMessageType());
    assertEquals(VALID_E2EE_PAYLOAD, dto.getE2eePayload());
    assertEquals(10L, dto.getGroupId());
    assertEquals("user1", dto.getSenderUsername());
  }

  @Test
  void shouldMapPollMessageWithPollPayload() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    Poll poll = Poll.builder().id(99L).question("Question?").author(sender).build();
    PollResponseDto pollResponse = new PollResponseDto(99L, "Question?", false, List.of());
    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setGroup(group);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    message.setMessageType(MessageType.POLL);
    message.setPoll(poll);

    when(pollService.toResponseDto(poll)).thenReturn(pollResponse);

    ChatMessageDto dto = chatService.toDto(message);

    assertEquals(MessageType.POLL, dto.getMessageType());
    assertEquals(pollResponse, dto.getPoll());
    assertNull(dto.getE2eePayload());
  }

  @Test
  void shouldFailSaveMessage_WhenSenderNotFoundById() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(999L);
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenSenderNotFoundByUsername() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderUsername("unknown");
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenNoSenderInfo() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldPersistClientMessageIdWhenSaving() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);
    dto.setClientMessageId("client-uuid-123");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertEquals("client-uuid-123", result.getClientMessageId());
  }

  @Test
  void shouldAllowNullClientMessageId_forBackwardCompatibility() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);
    // clientMessageId intentionally left unset, simulating a pre-migration client

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertNull(result.getClientMessageId());
  }

  @Test
  void shouldEchoClientMessageIdInToDto() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setGroup(group);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    message.setE2eePayload(VALID_E2EE_PAYLOAD);
    message.setSenderDeviceId(SENDER_DEVICE_ID);
    message.setClientMessageId("client-uuid-123");

    ChatMessageDto dto = chatService.toDto(message);

    assertEquals("client-uuid-123", dto.getClientMessageId());
  }

  @Test
  void shouldReturnNullClientMessageId_forLegacyMessagesWithoutOne() {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setGroup(group);
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    message.setE2eePayload(VALID_E2EE_PAYLOAD);
    message.setSenderDeviceId(SENDER_DEVICE_ID);

    ChatMessageDto dto = chatService.toDto(message);

    assertNull(dto.getClientMessageId());
  }

  @Test
  void shouldFailSaveMessage_WhenGroupNotFound() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(999L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenNoChatTarget() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));

    assertThrows(GroupNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenEncryptionDataMissing() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload(null);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));

    assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenPrivateChatNotFound() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setPrivateChatId(99L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(privateChatRepository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(PrivateChatNotFoundException.class, () -> chatService.saveMessage(dto));

    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenSenderDeviceIdMissing() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload(VALID_E2EE_PAYLOAD);
    dto.setSenderDeviceId(null);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));

    assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenE2eePayloadBlank() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setE2eePayload("");
    dto.setSenderDeviceId(SENDER_DEVICE_ID);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));

    assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }
}
