package vaultWeb.controllers;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
  public NotificationDto markRead(@PathVariable Long id, Principal principal,
      @RequestBody Map<String, Boolean> body) {
    return notificationService.markRead(principal.getName(), id,
        body.getOrDefault("read", true));
  }

  @PostMapping("/mark-all-read")
  public ResponseEntity<Map<String, Integer>> markAllRead(Principal principal) {
    return ResponseEntity.ok(Map.of("updated",
        notificationService.markAllRead(principal.getName())));
  }

  @GetMapping("/preferences")
  public List<NotificationPreferenceDto> preferences(Principal principal) {
    return notificationService.preferences(principal.getName());
  }

  @PutMapping("/preferences/{source}")
  public NotificationPreferenceDto setPreference(Principal principal, @PathVariable String source,
      @RequestBody Map<String, Boolean> body) {
    return notificationService.setMuted(principal.getName(), source,
        body.getOrDefault("muted", false));
  }
}
