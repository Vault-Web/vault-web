package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Device;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.DeviceRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.services.ChatService;
import vaultWeb.services.PrivateChatService;

@ExtendWith(MockitoExtension.class)
class PrivateChatControllerTest {

  @Mock private PrivateChatService privateChatService;
  @Mock private ChatMessageRepository chatMessageRepository;
  @Mock private DeviceRepository deviceRepository;
  @Mock private PrivateChatRepository privateChatRepository;
  @Mock private ChatService chatService;

  @InjectMocks private PrivateChatController privateChatController;

  private User createUser(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  @Test
  void shouldGetPrivateChatDevices_WhenUserIsParticipant() {
    User alice = createUser(1L, "alice");
    User bob = createUser(2L, "bob");
    PrivateChat chat = new PrivateChat();
    chat.setId(7L);
    chat.setUser1(alice);
    chat.setUser2(bob);
    Device device = new Device();
    device.setDeviceId("alice-dev-1");
    device.setPublicKey("pk");
    device.setUser(alice);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("alice");
    when(privateChatRepository.findById(7L)).thenReturn(Optional.of(chat));
    when(deviceRepository.findByUserIn(List.of(alice, bob))).thenReturn(List.of(device));

    List<DeviceDto> response = privateChatController.getPrivateChatDevices(7L, authentication);

    assertEquals(1, response.size());
    assertEquals("alice-dev-1", response.get(0).getDeviceId());
    verify(deviceRepository, times(1)).findByUserIn(List.of(alice, bob));
  }

  @Test
  void shouldRejectGetPrivateChatDevices_WhenUserIsNotParticipant() {
    User alice = createUser(1L, "alice");
    User bob = createUser(2L, "bob");
    PrivateChat chat = new PrivateChat();
    chat.setId(7L);
    chat.setUser1(alice);
    chat.setUser2(bob);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("mallory");
    when(privateChatRepository.findById(7L)).thenReturn(Optional.of(chat));

    assertThrows(
        AccessDeniedException.class,
        () -> privateChatController.getPrivateChatDevices(7L, authentication));
    verify(deviceRepository, times(0)).findByUserIn(any());
  }

  @Test
  void shouldRejectGetPrivateChatDevices_WhenChatNotFound() {
    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(privateChatRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(
        PrivateChatNotFoundException.class,
        () -> privateChatController.getPrivateChatDevices(999L, authentication));
    verify(deviceRepository, times(0)).findByUserIn(any());
  }

  @Test
  void shouldRejectGetPrivateChatDevices_WhenUnauthenticated() {
    assertThrows(
        UnauthorizedException.class, () -> privateChatController.getPrivateChatDevices(7L, null));
    verify(privateChatRepository, times(0)).findById(any());
  }

  @Test
  void shouldGetPrivateChatMessages_WhenUserIsParticipant() {
    User alice = createUser(1L, "alice");
    User bob = createUser(2L, "bob");
    PrivateChat chat = new PrivateChat();
    chat.setId(7L);
    chat.setUser1(alice);
    chat.setUser2(bob);

    ChatMessage message = new ChatMessage();
    message.setPrivateChat(chat);
    message.setSender(alice);
    message.setSenderDeviceId("alice-device");
    message.setE2eePayload("{\"v\":1}");
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("alice");
    when(privateChatRepository.findById(7L)).thenReturn(Optional.of(chat));
    when(chatMessageRepository.findByPrivateChatIdAndDeletedFalseOrderByTimestampAsc(7L))
        .thenReturn(List.of(message));
    ChatMessageDto dto = new ChatMessageDto();
    dto.setPrivateChatId(7L);
    dto.setSenderUsername("alice");
    dto.setE2eePayload("{\"v\":1}");
    when(chatService.toDto(message)).thenReturn(dto);

    List<ChatMessageDto> response =
        privateChatController.getPrivateChatMessages(7L, authentication);

    assertEquals(1, response.size());
    assertEquals("{\"v\":1}", response.get(0).getE2eePayload());
    assertEquals("alice", response.get(0).getSenderUsername());
  }

  @Test
  void shouldRejectGetPrivateChatMessages_WhenUserIsNotParticipant() {
    User alice = createUser(1L, "alice");
    User bob = createUser(2L, "bob");
    PrivateChat chat = new PrivateChat();
    chat.setId(7L);
    chat.setUser1(alice);
    chat.setUser2(bob);

    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    when(authentication.getName()).thenReturn("mallory");
    when(privateChatRepository.findById(7L)).thenReturn(Optional.of(chat));

    assertThrows(
        AccessDeniedException.class,
        () -> privateChatController.getPrivateChatMessages(7L, authentication));
  }

  @Test
  void shouldRejectGetPrivateChatMessages_WhenUnauthenticated() {
    assertThrows(
        UnauthorizedException.class, () -> privateChatController.getPrivateChatMessages(7L, null));
    verify(privateChatRepository, times(0)).findById(any());
  }
}
