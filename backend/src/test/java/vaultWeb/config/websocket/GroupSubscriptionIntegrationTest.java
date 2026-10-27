package vaultWeb.config.websocket;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import vaultWeb.dtos.ChatErrorDto;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.User;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.JwtUtil;
import vaultWeb.services.GroupService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GroupSubscriptionIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private SimpMessagingTemplate broker;
  @Autowired private GroupMemberRepository memberships;
  @Autowired private GroupRepository groups;
  @Autowired private UserRepository users;
  @Autowired private GroupService groupService;
  private final List<Group> createdGroups = new ArrayList<>();
  private final List<User> createdUsers = new ArrayList<>();
  private final List<StompSession> sessions = new ArrayList<>();
  private final List<WebSocketStompClient> clients = new ArrayList<>();
  private final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();

  @AfterEach
  void closeClients() {
    sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    clients.forEach(WebSocketStompClient::stop);
    for (Group group : createdGroups) {
      memberships.deleteAll(memberships.findAllByGroup(group));
      groups.deleteById(group.getId());
    }
    createdUsers.forEach(user -> users.deleteById(user.getId()));
  }

  @Test
  void rejectedSubscriptionsDoNotLeakEventsOrDisconnectOtherSubscriptionsOrNotifyOtherTabs()
      throws Exception {
    User alice = createUser("alice");
    Group allowedGroup = createGroup(alice);
    Group forbiddenGroup = createGroup(alice);
    memberships.save(new GroupMember(allowedGroup, alice, Role.USER));
    String allowedTopic = "/topic/group/" + allowedGroup.getId();
    String forbiddenTopic = "/topic/group/" + forbiddenGroup.getId();
    StompSession first = connect(alice);
    StompSession otherTab = connect(alice);
    BlockingQueue<String> errors = subscribe(first, "/user/queue/errors");
    BlockingQueue<String> otherErrors = subscribe(otherTab, "/user/queue/errors");
    // Observe delivery to establish that both error subscriptions reached the real broker.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              broker.convertAndSendToUser(alice.getUsername(), "/queue/errors", "ready");
              return errors.contains("ready") && otherErrors.contains("ready");
            });

    BlockingQueue<String> allowed = subscribe(first, allowedTopic);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              broker.convertAndSend(allowedTopic, "ready");
              return allowed.contains("ready");
            });
    for (String destination :
        List.of(
            forbiddenTopic, forbiddenTopic + "/deleted", forbiddenTopic + "/typing", "/topic/**")) {
      BlockingQueue<String> forbidden = subscribe(first, destination);
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  errors.stream()
                      .anyMatch(error -> error.contains("destination=" + destination + "]")));

      broker.convertAndSend(forbiddenTopic, "private-message");
      broker.convertAndSend(forbiddenTopic + "/deleted", "private-deletion");
      broker.convertAndSend(forbiddenTopic + "/typing", "private-typing");
      String marker = "still-connected-after-" + destination;
      broker.convertAndSend(allowedTopic, marker);
      await().atMost(Duration.ofSeconds(5)).until(() -> allowed.contains(marker));

      assertNull(forbidden.poll(200, TimeUnit.MILLISECONDS));
      assertTrue(first.isConnected());
      assertTrue(otherTab.isConnected());
    }
    assertTrue(otherErrors.stream().allMatch("ready"::equals));
    assertTrue(failures.isEmpty(), () -> "Unexpected STOMP/transport failures: " + failures);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leavingOrRemovingMembershipStopsExistingSubscriptionsAcrossTabs(boolean leave)
      throws Exception {
    User alice = createUser("alice");
    User bob = createUser("bob");
    Group revokedGroup = createGroup(bob);
    Group retainedGroup = createGroup(alice);
    memberships.save(new GroupMember(revokedGroup, alice, Role.USER));
    memberships.save(new GroupMember(revokedGroup, bob, Role.ADMIN));
    memberships.save(new GroupMember(retainedGroup, alice, Role.USER));
    StompSession first = connect(alice);
    StompSession otherTab = connect(alice);
    StompSession remainingMember = connect(bob);
    String topic = "/topic/group/" + revokedGroup.getId();
    List<String> destinations =
        List.of(topic, topic + "/deleted", topic + "/typing", topic + "/events/updated");
    List<BlockingQueue<String>> firstTabMessages = new ArrayList<>();
    List<BlockingQueue<String>> otherTabMessages = new ArrayList<>();
    List<BlockingQueue<String>> bobMessages = new ArrayList<>();
    for (String destination : destinations) {
      firstTabMessages.add(subscribe(first, destination));
      otherTabMessages.add(subscribe(otherTab, destination));
      bobMessages.add(subscribe(remainingMember, destination));
    }
    String retainedTopic = "/topic/group/" + retainedGroup.getId();
    BlockingQueue<String> retained = subscribe(first, retainedTopic);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              for (String destination : destinations) {
                broker.convertAndSend(destination, "ready");
              }
              broker.convertAndSend(retainedTopic, "ready");
              return retained.contains("ready")
                  && firstTabMessages.stream().allMatch(queue -> queue.contains("ready"))
                  && otherTabMessages.stream().allMatch(queue -> queue.contains("ready"))
                  && bobMessages.stream().allMatch(queue -> queue.contains("ready"));
            });

    if (leave) {
      groupService.leaveGroup(revokedGroup.getId(), alice);
    } else {
      groupService.removeMember(revokedGroup.getId(), alice.getId());
    }
    assertFalse(
        memberships.existsByGroupIdAndUserUsername(revokedGroup.getId(), alice.getUsername()));
    for (String destination : destinations) {
      broker.convertAndSend(destination, "after-revocation");
    }
    broker.convertAndSend(retainedTopic, "still-authorized");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                retained.contains("still-authorized")
                    && bobMessages.stream().allMatch(queue -> queue.contains("after-revocation")));
    // Observe a quiet window after the other recipient has received every event.
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              for (BlockingQueue<String> queue : firstTabMessages) {
                assertFalse(queue.contains("after-revocation"));
              }
              for (BlockingQueue<String> queue : otherTabMessages) {
                assertFalse(queue.contains("after-revocation"));
              }
            });
    assertTrue(first.isConnected());
    assertTrue(otherTab.isConnected());
    assertTrue(remainingMember.isConnected());
    assertTrue(failures.isEmpty(), () -> "Unexpected STOMP/transport failures: " + failures);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clientsCannotPublishForgedGroupEventsDirectlyToTheBroker(boolean attackerIsMember)
      throws Exception {
    User alice = createUser("alice");
    User bob = createUser("bob");
    Group group = createGroup(alice);
    memberships.save(new GroupMember(group, alice, Role.ADMIN));
    if (attackerIsMember) {
      memberships.save(new GroupMember(group, bob, Role.USER));
    }
    StompSession receiver = connect(alice);
    StompSession attacker = connect(bob);
    String topic = "/topic/group/" + group.getId();
    List<String> destinations =
        List.of(topic, topic + "/deleted", topic + "/typing", topic + "/events/updated");
    List<BlockingQueue<String>> received = new ArrayList<>();
    for (String destination : destinations) {
      received.add(subscribe(receiver, destination));
    }
    BlockingQueue<String> errors = subscribe(attacker, "/user/queue/errors");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              broker.convertAndSendToUser(bob.getUsername(), "/queue/errors", "ready");
              destinations.forEach(destination -> broker.convertAndSend(destination, "ready"));
              return errors.contains("ready")
                  && received.stream().allMatch(queue -> queue.contains("ready"));
            });
    for (String destination : destinations) {
      attacker.send(destination, "forged-event");
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  errors.stream()
                      .anyMatch(
                          error ->
                              error.contains("application endpoints")
                                  && error.contains("destination=" + destination + "]")));
      broker.convertAndSend(destination, "server-event");
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> received.stream().allMatch(queue -> queue.contains("server-event")));
    for (BlockingQueue<String> messages : received) {
      assertFalse(messages.contains("forged-event"));
    }
    assertTrue(attacker.isConnected());
    assertTrue(receiver.isConnected());
    assertTrue(failures.isEmpty(), () -> "Unexpected STOMP/transport failures: " + failures);
  }

  private User createUser(String prefix) {
    User user = new User();
    user.setUsername(prefix + "-" + UUID.randomUUID());
    user.setPassword("not-used-for-token-authentication");
    user = users.save(user);
    createdUsers.add(user);
    return user;
  }

  private Group createGroup(User owner) {
    Group group = new Group();
    group.setName("subscription-test");
    group.setCreatedBy(owner);
    group = groups.save(group);
    createdGroups.add(group);
    return group;
  }

  private StompSession connect(User user) throws Exception {
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
