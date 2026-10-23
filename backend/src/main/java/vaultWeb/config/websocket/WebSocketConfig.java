package vaultWeb.config.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import vaultWeb.security.JwtUtil;

/**
 * WebSocket configuration enabling STOMP messaging for real-time chat functionality. Sets up the
 * message broker with an in-memory topic destination and registers the WebSocket endpoint with
 * SockJS fallback support.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final JwtUtil jwtUtil;
  private final GroupSubscriptionInterceptor groupSubscriptionInterceptor;

  /**
   * Configure message broker with a simple in-memory broker for topics and application destination
   * prefix for incoming messages.
   *
   * @param config the message broker registry
   */
  @Override
  public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic", "/queue");
    config.setApplicationDestinationPrefixes("/app");
    config.setUserDestinationPrefix("/user");
  }

  /**
   * Register STOMP WebSocket endpoint at "/ws-chat" with SockJS fallback and allow all origins
   * (adjust for production).
   *
   * @param registry the STOMP endpoint registry
   */
  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws-chat")
        .addInterceptors(new JwtHandshakeInterceptor(jwtUtil))
        .setAllowedOriginPatterns(
            "http://localhost",
            "https://localhost",
            "http://localhost:*",
            "https://localhost:*",
            "http://127.0.0.1",
            "https://127.0.0.1",
            "http://127.0.0.1:*",
            "https://127.0.0.1:*",
            "http://100.*.*.*",
            "https://100.*.*.*",
            "http://100.*.*.*:*",
            "https://100.*.*.*:*",
            "http://*.vpn.internal",
            "https://*.vpn.internal",
            "http://*.vpn.internal:*",
            "https://*.vpn.internal:*",
            "http://vault.*",
            "https://vault.*",
            "http://vault.*:*",
            "https://vault.*:*")
        .withSockJS();
  }

  /**
   * Configure the inbound channel for STOMP messages from clients. This method adds a
   * ChannelInterceptor that intercepts incoming messages before they reach message-handling
   * methods. During the CONNECT command, it extracts the JWT token from the "Authorization" header,
   * validates it, and sets the corresponding Spring Security Authentication object as the user for
   * the session. This enables per-user messaging and security checks for WebSocket messages.
   *
   * @param registration the client inbound channel registration
   */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
        new ChannelInterceptor() {
          @Override
          public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

            if (accessor == null) {
              return message;
            }

            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
              String authHeader = accessor.getFirstNativeHeader("Authorization");

              if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                if (jwtUtil.validateToken(token)) {
                  Authentication auth = jwtUtil.getAuthentication(token);
                  accessor.setUser(auth);
                }
              }
            }

            return message;
          }
        },
        groupSubscriptionInterceptor);
  }
}
