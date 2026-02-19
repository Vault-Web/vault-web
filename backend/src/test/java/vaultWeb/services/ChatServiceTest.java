package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.DecryptionFailedException;
import vaultWeb.exceptions.EncryptionFailedException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.EncryptionUtil;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatMessageRepository chatMessageRepository;

  @Mock private UserRepository userRepository;

  @Mock private GroupRepository groupRepository;

  @Mock private PrivateChatRepository privateChatRepository;

  @Mock private EncryptionUtil encryptionUtil;

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
  void shouldSaveGroupMessageSuccessfully() throws Exception {
    User sender = createUser(1L, "user1");
    Group group = createGroup(10L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setContent("Hello World");

    EncryptionUtil.EncryptResult encryptResult =
        new EncryptionUtil.EncryptResult("encryptedText", "randomIV");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(encryptionUtil.encrypt("Hello World")).thenReturn(encryptResult);
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertNotNull(result);
    assertEquals("encryptedText", result.getCipherText());
    assertEquals("randomIV", result.getIv());
    assertEquals(sender, result.getSender());
    assertEquals(group, result.getGroup());
    verify(chatMessageRepository).save(any(ChatMessage.class));
  }

  @Test
  void shouldSavePrivateChatMessageSuccessfully() throws Exception {
    User sender = createUser(1L, "user1");
    PrivateChat privateChat = createPrivateChat(5L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderUsername("user1");
    dto.setPrivateChatId(5L);
    dto.setContent("Private message");

    EncryptionUtil.EncryptResult encryptResult =
        new EncryptionUtil.EncryptResult("encryptedPrivate", "privateIV");

    when(userRepository.findByUsername("user1")).thenReturn(Optional.of(sender));
    when(privateChatRepository.findById(5L)).thenReturn(Optional.of(privateChat));
    when(encryptionUtil.encrypt("Private message")).thenReturn(encryptResult);
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto);

    assertNotNull(result);
    assertEquals(privateChat, result.getPrivateChat());
    verify(chatMessageRepository).save(any(ChatMessage.class));
  }


  @Test
  void shouldSaveEncryptedPrivateChatMessageSuccessfully() {
    User sender = createUser(1L, "user1");
    PrivateChat privateChat = createPrivateChat(5L);
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderUsername("user1");
    dto.setPrivateChatId(5L);
    dto.setCipherText("encryptedPrivate");
    dto.setIv("privateIV");

    when(userRepository.findByUsername("user1")).thenReturn(Optional.of(sender));
    when(privateChatRepository.findById(5L)).thenReturn(Optional.of(privateChat));
    when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));

    ChatMessage result = chatService.saveMessage(dto, true);

    assertNotNull(result);
    assertEquals("encryptedPrivate", result.getCipherText());
    assertEquals("privateIV", result.getIv());
    assertEquals(privateChat, result.getPrivateChat());
    verify(encryptionUtil, never()).encrypt(any());
    verify(chatMessageRepository).save(any(ChatMessage.class));
  }

  @Test
  void shouldFailSaveMessage_WhenEncryptedPayloadMissing() {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setPrivateChatId(5L);

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));

    assertThrows(EncryptionFailedException.class, () -> chatService.saveMessage(dto, true));

    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenSenderNotFoundById() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(999L);
    dto.setGroupId(10L);
    dto.setContent("Hello");

    when(userRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenSenderNotFoundByUsername() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderUsername("unknown");
    dto.setGroupId(10L);
    dto.setContent("Hello");

    when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenNoSenderInfo() {
    ChatMessageDto dto = new ChatMessageDto();
    dto.setGroupId(10L);
    dto.setContent("Hello");

    assertThrows(UserNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenGroupNotFound() throws Exception {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(999L);
    dto.setContent("Hello");

    EncryptionUtil.EncryptResult encryptResult =
        new EncryptionUtil.EncryptResult("encrypted", "iv");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(encryptionUtil.encrypt("Hello")).thenReturn(encryptResult);
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenNoChatTarget() throws Exception {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setContent("Hello");

    EncryptionUtil.EncryptResult encryptResult =
        new EncryptionUtil.EncryptResult("encrypted", "iv");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(encryptionUtil.encrypt("Hello")).thenReturn(encryptResult);

    assertThrows(GroupNotFoundException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldFailSaveMessage_WhenEncryptionFails() throws Exception {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setGroupId(10L);
    dto.setContent("Hello");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(encryptionUtil.encrypt("Hello")).thenThrow(new RuntimeException("Encryption error"));

    assertThrows(EncryptionFailedException.class, () -> chatService.saveMessage(dto));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  void shouldDecryptMessageSuccessfully() throws Exception {
    when(encryptionUtil.decrypt("cipherText", "iv")).thenReturn("decrypted message");

    String result = chatService.decrypt("cipherText", "iv");

    assertEquals("decrypted message", result);
  }

  @Test
  void shouldFailDecrypt_WhenDecryptionFails() throws Exception {
    when(encryptionUtil.decrypt("badCipher", "badIv"))
        .thenThrow(new RuntimeException("Decryption error"));

    assertThrows(DecryptionFailedException.class, () -> chatService.decrypt("badCipher", "badIv"));
  }

  @Test
  void shouldFailSaveMessage_WhenPrivateChatNotFound() throws Exception {
    User sender = createUser(1L, "user1");
    ChatMessageDto dto = new ChatMessageDto();
    dto.setSenderId(1L);
    dto.setPrivateChatId(99L);
    dto.setContent("Hello");

    EncryptionUtil.EncryptResult encryptResult =
        new EncryptionUtil.EncryptResult("encrypted", "iv");

    when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
    when(privateChatRepository.findById(99L)).thenReturn(Optional.empty());
    when(encryptionUtil.encrypt("Hello")).thenReturn(encryptResult);

    assertThrows(GroupNotFoundException.class, () -> chatService.saveMessage(dto));

    verify(chatMessageRepository, never()).save(any());
  }
}
