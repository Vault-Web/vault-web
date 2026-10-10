package vaultWeb.controllers;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vaultWeb.dtos.NotificationDto;
import vaultWeb.dtos.NotificationPreferenceDto;
import vaultWeb.services.NotificationService;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationService notificationService;

  @GetMapping
  public List<NotificationDto> list(
      Principal principal,
      @RequestParam(required = false) String source,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "50") int limit) {
    return notificationService.list(principal.getName(), source, unreadOnly, limit);
  }

  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount(Principal principal) {
    return Map.of("count", notificationService.unreadCount(principal.getName()));
  }

  @PatchMapping("/{id}/read")
  public NotificationDto markRead(
      @PathVariable Long id,
      Principal principal,
      @RequestBody(required = false) Map<String, Boolean> body) {
    Boolean read = body == null ? null : body.get("read");
    if (read == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'read' must be a boolean");
    }
    return notificationService.markRead(principal.getName(), id, read);
  }

  @PostMapping("/mark-all-read")
  public ResponseEntity<Map<String, Integer>> markAllRead(Principal principal) {
    return ResponseEntity.ok(
        Map.of("updated", notificationService.markAllRead(principal.getName())));
  }

  @GetMapping("/preferences")
  public List<NotificationPreferenceDto> preferences(Principal principal) {
    return notificationService.preferences(principal.getName());
  }

  @PutMapping("/preferences/{source}")
  public NotificationPreferenceDto setPreference(
      Principal principal,
      @PathVariable String source,
      @RequestBody(required = false) Map<String, Boolean> body) {
    Boolean muted = body == null ? null : body.get("muted");
    if (muted == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'muted' must be a boolean");
    }
    return notificationService.setMuted(principal.getName(), source, muted);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleNotificationRequestException(
      ResponseStatusException exception) {
    String message =
        exception.getReason() == null ? "Request rejected" : exception.getReason();
    return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
  }

}
