package vaultWeb.config.websocket;

import io.jsonwebtoken.JwtException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import vaultWeb.security.JwtUtil;
import vaultWeb.security.pat.PatTokens;

/** Authenticates STOMP before any application handler or broker subscription can run. */
@Component
@RequiredArgsConstructor
public class StompAuthenticationInterceptor implements ChannelInterceptor {

  private final JwtUtil jwtUtil;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      throw denied();
    }
    if (accessor.getCommand() == StompCommand.CONNECT) {
      List<String> headers = accessor.getNativeHeader("Authorization");
      accessor.removeNativeHeader("Authorization");
      if (headers == null || headers.size() != 1 || !headers.getFirst().startsWith("Bearer ")) {
        throw denied();
      }
      String token = headers.getFirst().substring(7);
      try {
        // Personal access tokens are never accepted for WebSocket/chat access.
        if (token.isBlank() || PatTokens.hasPrefix(token) || !jwtUtil.validateToken(token)) {
          throw denied();
        }
        Authentication authentication = jwtUtil.getAuthentication(token);
        if (!authenticated(authentication)) {
          throw denied();
        }
        // Spring saves this principal and attaches it to later frames on this session.
        accessor.setUser(authentication);
      } catch (JwtException | IllegalArgumentException exception) {
        // Do not include the credential or token parser's details in STOMP ERROR frames.
        throw denied();
      }
    } else if (accessor.getCommand() != StompCommand.DISCONNECT
        && (!(accessor.getUser() instanceof Authentication authentication)
            || !authenticated(authentication))) {
      throw denied();
    }
    return message;
  }

  private boolean authenticated(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null
        && !authentication.getName().isBlank();
  }

  private AccessDeniedException denied() {
    return new AccessDeniedException("STOMP authentication required");
  }
}
