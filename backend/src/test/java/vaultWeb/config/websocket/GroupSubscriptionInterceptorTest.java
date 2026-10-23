package vaultWeb.config.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.repositories.GroupMemberRepository;

@ExtendWith(MockitoExtension.class)
class GroupSubscriptionInterceptorTest {
  @Mock private GroupMemberRepository repository;
  @Mock private ObjectProvider<SimpMessagingTemplate> provider;
  @Mock private SimpMessagingTemplate template;
  @Mock private MessageChannel channel;
  private GroupSubscriptionInterceptor interceptor;
  private final Principal alice = new UsernamePasswordAuthenticationToken("alice", null, List.of());

  @BeforeEach
  void setUp() {
    interceptor = new GroupSubscriptionInterceptor(repository, provider);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/topic/group/42",
        "/topic/group/42/deleted",
        "/topic/group/42/typing",
        "/topic/group/42/events/updated"
      })
  void allowsMembersAcrossGroupDestinations(String destination) {
    when(repository.existsByGroupIdAndUserUsername(42L, "alice")).thenReturn(true);
    Message<byte[]> message = frame(StompCommand.SUBSCRIBE, destination, alice);
    assertSame(message, interceptor.preSend(message, channel));
    verify(repository).existsByGroupIdAndUserUsername(42L, "alice");
    verifyNoInteractions(provider);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/topic/group/42",
        "/topic/group/42/deleted",
        "/topic/group/42/typing",
        "/topic/group/42/events/updated"
      })
  void rejectsNonMembersWithSessionScopedError(String destination) {
    when(provider.getObject()).thenReturn(template);
    assertNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, alice), channel));
    ArgumentCaptor<ChatErrorDto> error = ArgumentCaptor.forClass(ChatErrorDto.class);
    ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.captor();
    verify(template)
        .convertAndSendToUser(
            eq("session-1"), eq("/queue/errors"), error.capture(), headers.capture());
    assertTrue(error.getValue().getError().contains("Not allowed"));
    assertTrue(error.getValue().getError().contains(destination));
    assertTrue(error.getValue().getError().contains("subscription-1"));
    assertEquals("session-1", headers.getValue().get("simpSessionId"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "/topic/group",
        "/topic/group/",
        "/topic/group/abc",
        "/topic/group/999999999999999999999999",
        "/topic/group/*",
        "/topic/group/42/**",
        "/topic/**",
        "/**",
        "/topic/{kind}/42"
      })
  void rejectsMalformedAndPatternDestinationsWithoutDatabaseLookup(String destination) {
    when(provider.getObject()).thenReturn(template);
    assertNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, alice), channel));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMissingUntrustedAndUnauthenticatedPrincipals() {
    when(provider.getObject()).thenReturn(template);
    Principal[] principals = {
      null,
      () -> "alice",
      new UsernamePasswordAuthenticationToken("alice", null),
      new UsernamePasswordAuthenticationToken(" ", null, List.of()),
      new AnonymousAuthenticationToken(
          "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))
    };
    for (Principal principal : principals) {
      assertNull(
          interceptor.preSend(
              frame(StompCommand.SUBSCRIBE, "/topic/group/42", principal), channel));
    }
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/user/queue/errors",
        "/user/queue/private",
        "/user/queue/typing",
        "/topic/group-announcements"
      })
  void leavesOtherConcreteDestinationsUnchanged(String destination) {
    Message<byte[]> message = frame(StompCommand.SUBSCRIBE, destination, alice);
    assertSame(message, interceptor.preSend(message, channel));
    verifyNoInteractions(repository, provider);
  }

  @Test
  void leavesOtherCommandsAndNonStompMessagesUnchanged() {
    for (StompCommand command :
        List.of(
            StompCommand.CONNECT,
            StompCommand.SEND,
            StompCommand.UNSUBSCRIBE,
            StompCommand.DISCONNECT)) {
      Message<byte[]> message = frame(command, "/topic/group/42", alice);
      assertSame(message, interceptor.preSend(message, channel));
    }
    Message<byte[]> message = MessageBuilder.withPayload(new byte[0]).build();
    assertSame(message, interceptor.preSend(message, channel));
    verifyNoInteractions(repository, provider);
  }

  private Message<byte[]> frame(StompCommand command, String destination, Principal principal) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(command);
    headers.setSessionId("session-1");
    headers.setSubscriptionId("subscription-1");
    headers.setDestination(destination);
    headers.setUser(principal);
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }
}
