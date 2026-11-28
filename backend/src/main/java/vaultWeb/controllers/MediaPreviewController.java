package vaultWeb.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vaultWeb.services.MediaPreviewService;
import vaultWeb.services.auth.AuthService;

@RestController
@RequiredArgsConstructor
public class MediaPreviewController {
  private final AuthService authService;
  private final MediaPreviewService previews;

  // The browser only sends the HttpOnly refresh cookie within the existing refresh path.
  @PostMapping("/api/auth/refresh/media")
  public ResponseEntity<Void> renew(
      @RequestBody Renewal request,
      @CookieValue(name = "refresh_token", required = false) String refreshToken,
      Authentication authentication) {
    if (request.id() == null || request.id().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    if (!authService.hasActiveRefreshSession(refreshToken, authentication.getName())) {
      return ResponseEntity.status(403).build();
    }
    return previews.renew(request.id(), authentication.getName())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  public record Renewal(String id) {}
}
