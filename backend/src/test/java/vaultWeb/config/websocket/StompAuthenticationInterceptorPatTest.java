package vaultWeb.config.websocket;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import vaultWeb.security.JwtUtil;

class StompAuthenticationInterceptorPatTest {

  private static final String PAT = "vw_pat_" + "A".repeat(12) + "_" + "A".repeat(43);

  @Test
  void connect_withPersonalAccessToken_isRejectedWithoutJwtParsing() {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    StompAuthenticationInterceptor interceptor = new StompAuthenticationInterceptor(jwtUtil);

    StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
    connect.setNativeHeader("Authorization", "Bearer " + PAT);
    connect.setLeaveMutable(true);

    assertThrows(
        AccessDeniedException.class,
        () ->
            interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), null));
    assertNull(connect.getUser());
    verify(jwtUtil, never()).validateToken(any());
  }
}
