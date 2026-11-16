package vaultWeb.config.websocket;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vaultWeb.models.User;
import vaultWeb.security.JwtUtil;

class StompAuthenticationInterceptorTest {
  private static final String SECRET = "synthetic_websocket_access_key_32_chars_only";
  private final JwtUtil jwtUtil = new JwtUtil(SECRET, "synthetic_refresh_key_32_chars_only");
  private final StompAuthenticationInterceptor interceptor =
      new StompAuthenticationInterceptor(jwtUtil);

  private Message<byte[]> frame(StompHeaderAccessor accessor) {
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void authenticatesAnExistingAccessTokenAndPreservesThePrincipalForLaterFrames() {
    User user = new User();
    user.setUsername("alice");
    user.setId(1L);
    StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
    connect.setNativeHeader("Authorization", "Bearer " + jwtUtil.generateToken(user));
    Message<byte[]> message = frame(connect);
    assertSame(message, interceptor.preSend(message, null));
    assertEquals("alice", connect.getUser().getName());
    assertNull(connect.getNativeHeader("Authorization"));
    for (StompCommand command : List.of(StompCommand.SEND, StompCommand.SUBSCRIBE)) {
      StompHeaderAccessor subsequent = StompHeaderAccessor.create(command);
      subsequent.setUser(connect.getUser());
      Message<byte[]> next = frame(subsequent);
      assertSame(next, interceptor.preSend(next, null));
    }
  }

  @Test
  void rejectsMissingEmptyMalformedExpiredWronglySignedAndDuplicateCredentials() {
    String expired =
        Jwts.builder()
            .setSubject("alice")
            .setExpiration(new Date(0))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();
    String wrongSignature =
        Jwts.builder()
            .setSubject("alice")
            .signWith(Keys.hmacShaKeyFor("different_synthetic_signing_key_32_chars".getBytes()))
            .compact();
    for (String header :
        List.of(
            "",
            "Basic abc",
            "Bearer ",
            "Bearer invalid",
            "Bearer " + expired,
            "Bearer " + wrongSignature)) {
      StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
      accessor.setNativeHeader("Authorization", header);
      assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(accessor), null));
      assertNull(accessor.getUser());
    }
    StompHeaderAccessor missing = StompHeaderAccessor.create(StompCommand.CONNECT);
    assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(missing), null));
    StompHeaderAccessor duplicate = StompHeaderAccessor.create(StompCommand.CONNECT);
    duplicate.addNativeHeader("Authorization", "Bearer invalid");
    duplicate.addNativeHeader("Authorization", "Bearer invalid");
    assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(duplicate), null));
  }

  @Test
  void rejectsUnauthenticatedMessagingAndAllowsTransportCleanup() {
    for (StompCommand command :
        List.of(
            StompCommand.SEND,
            StompCommand.SUBSCRIBE,
            StompCommand.UNSUBSCRIBE,
            StompCommand.ACK)) {
      StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
      assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(accessor), null));
      accessor.setUser(new UsernamePasswordAuthenticationToken("alice", null));
      assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame(accessor), null));
    }
    Message<byte[]> disconnect = frame(StompHeaderAccessor.create(StompCommand.DISCONNECT));
    assertSame(disconnect, interceptor.preSend(disconnect, null));
  }
}
