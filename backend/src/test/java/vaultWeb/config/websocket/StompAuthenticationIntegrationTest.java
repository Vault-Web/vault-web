package vaultWeb.config.websocket;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import vaultWeb.models.User;
import vaultWeb.security.JwtUtil;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompAuthenticationIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;

  private String token() {
    User user = new User();
    user.setId(123L);
    user.setUsername("synthetic-websocket-user");
    return jwtUtil.generateToken(user);
  }

  @Test
  void tokenFreeSockJsInfoIsAccessibleButDoesNotAuthenticateMessaging() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws-chat/info"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("websocket"));
    }
  }

  @Test
  void existingTokenAuthenticatesWebSocketAndXhrWithoutUrlCredentials() throws Exception {
    for (boolean xhr : List.of(false, true)) {
      WebSocketStompClient client = client(xhr);
      StompHeaders headers = new StompHeaders();
      headers.add("Authorization", "Bearer " + token());
      try {
        StompSession session =
            client
                .connectAsync(
                    "http://localhost:" + port + "/ws-chat",
                    new WebSocketHttpHeaders(),
                    headers,
                    new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        assertTrue(session.isConnected());
        session.disconnect();
      } finally {
        client.stop();
      }
    }
  }

  @Test
  void queryTokenCannotReplaceConnectCredentialsAndInvalidConnectIsRejected() {
    for (boolean xhr : List.of(false, true)) {
      for (boolean invalid : List.of(false, true)) {
        WebSocketStompClient client = client(xhr);
        StompHeaders headers = new StompHeaders();
        if (invalid) {
          headers.add("Authorization", "Bearer malformed-synthetic-token");
        }
        try {
          var connection =
              client.connectAsync(
                  "http://localhost:" + port + "/ws-chat?token=" + token(),
                  new WebSocketHttpHeaders(),
                  headers,
                  new StompSessionHandlerAdapter() {
                    @Override
                    public Type getPayloadType(StompHeaders frameHeaders) {
                      return byte[].class;
                    }
                  });
          assertThrows(ExecutionException.class, () -> connection.get(10, TimeUnit.SECONDS));
        } finally {
          client.stop();
        }
      }
    }
  }

  private WebSocketStompClient client(boolean xhr) {
    SockJsClient transport =
        new SockJsClient(
            xhr
                ? List.of(new RestTemplateXhrTransport(new RestTemplate()))
                : List.of(new WebSocketTransport(new StandardWebSocketClient())));
    WebSocketStompClient client = new WebSocketStompClient(transport);
    client.setDefaultHeartbeat(new long[] {0, 0});
    return client;
  }
}
