package vaultWeb.config.websocket;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.models.User;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.security.JwtUtil;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GroupSubscriptionIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private SimpMessagingTemplate broker;
  @MockitoBean private GroupMemberRepository memberships;
  private final List<StompSession> sessions = new ArrayList<>();
  private final List<WebSocketStompClient> clients = new ArrayList<>();
  private final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();

  @AfterEach
  void closeClients() {
    sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    clients.forEach(WebSocketStompClient::stop);
  }

  @Test
  void rejectedSubscriptionsDoNotLeakEventsOrDisconnectOtherSubscriptionsOrNotifyOtherTabs()
      throws Exception {
    when(memberships.existsByGroupIdAndUserUsername(42L, "alice")).thenReturn(true);
    StompSession first = connect();
    StompSession otherTab = connect();
    BlockingQueue<String> errors = subscribe(first, "/user/queue/errors");
    BlockingQueue<String> otherErrors = subscribe(otherTab, "/user/queue/errors");
    // Observe delivery to establish that both error subscriptions reached the real broker.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              broker.convertAndSendToUser("alice", "/queue/errors", "ready");
              return errors.contains("ready") && otherErrors.contains("ready");
            });

    BlockingQueue<String> allowed = subscribe(first, "/topic/group/42");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              broker.convertAndSend("/topic/group/42", "ready");
              return allowed.contains("ready");
            });
    for (String destination :
        List.of("/topic/group/7", "/topic/group/7/deleted", "/topic/group/7/typing", "/topic/**")) {
      BlockingQueue<String> forbidden = subscribe(first, destination);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  errors.stream()
                      .anyMatch(error -> error.contains("destination=" + destination + "]")));

      broker.convertAndSend("/topic/group/7", "private-message");
      broker.convertAndSend("/topic/group/7/deleted", "private-deletion");
      broker.convertAndSend("/topic/group/7/typing", "private-typing");
      String marker = "still-connected-after-" + destination;
      broker.convertAndSend("/topic/group/42", marker);
      await().atMost(Duration.ofSeconds(5)).until(() -> allowed.contains(marker));

      assertNull(forbidden.poll(200, TimeUnit.MILLISECONDS));
      assertTrue(first.isConnected());
      assertTrue(otherTab.isConnected());
    }
    assertTrue(otherErrors.stream().allMatch("ready"::equals));
    assertTrue(failures.isEmpty(), () -> "Unexpected STOMP/transport failures: " + failures);
  }

  private StompSession connect() throws Exception {
    User user = new User();
    user.setId(1L);
    user.setUsername("alice");
    String token = jwtUtil.generateToken(user);
    SockJsClient transport =
        new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
    WebSocketStompClient client = new WebSocketStompClient(transport);
    client.setMessageConverter(
        new CompositeMessageConverter(
            List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
    clients.add(client);
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + token);
    StompSession session =
        client
            .connectAsync(
                "http://localhost:" + port + "/ws-chat?token=" + token,
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {
                  @Override
                  public Type getPayloadType(StompHeaders headers) {
                    return headers.getContentType() != null
                            && MimeTypeUtils.APPLICATION_JSON.isCompatibleWith(
                                headers.getContentType())
                        ? ChatErrorDto.class
                        : String.class;
                  }

                  @Override
                  public void handleFrame(StompHeaders headers, Object payload) {
                    failures.add(new AssertionError("STOMP ERROR: " + payload));
                  }

                  @Override
                  public void handleTransportError(StompSession session, Throwable exception) {
                    failures.add(exception);
                  }

                  @Override
                  public void handleException(
                      StompSession session,
                      StompCommand command,
                      StompHeaders headers,
                      byte[] payload,
                      Throwable exception) {
                    failures.add(exception);
                  }
                })
            .get(10, TimeUnit.SECONDS);
    sessions.add(session);
    return session;
  }

  private BlockingQueue<String> subscribe(StompSession session, String destination) {
    BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return headers.getContentType() != null
                    && MimeTypeUtils.APPLICATION_JSON.isCompatibleWith(headers.getContentType())
                ? ChatErrorDto.class
                : String.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            messages.add(
                payload instanceof ChatErrorDto error ? error.getError() : (String) payload);
          }
        });
    return messages;
  }
}
