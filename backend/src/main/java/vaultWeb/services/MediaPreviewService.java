package vaultWeb.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Tracks short-lived access to a single file at a stable browser preview URL. */
@Service
public class MediaPreviewService {
  private final Cache<String, Preview> previews;
  private final Duration lifetime;
  private final Clock clock;

  @Autowired
  public MediaPreviewService(@Value("${app.media.token-ttl-seconds:300}") long seconds) {
    this(Duration.ofSeconds(seconds), Clock.systemUTC());
  }

  MediaPreviewService(Duration lifetime, Clock clock) {
    this.lifetime = lifetime;
    this.clock = clock;
    // Keep an expired preview's binding briefly so a backgrounded tab can recover after waking.
    this.previews =
        Caffeine.newBuilder()
            .maximumSize(1024)
            .expireAfterWrite(lifetime.plus(Duration.ofMinutes(15)))
            .build();
  }

  public String create(String username, String path) {
    String id = UUID.randomUUID().toString();
    previews.put(id, new Preview(username, path, clock.instant().plus(lifetime)));
    return id;
  }

  public Preview activePreview(String id) {
    Preview preview = previews.getIfPresent(id);
    return preview != null && clock.instant().isBefore(preview.expiresAt()) ? preview : null;
  }

  public boolean renew(String id, String username) {
    Preview preview =
        previews
            .asMap()
            .computeIfPresent(
                id,
                (key, current) -> {
                  if (!current.username().equals(username)) return current;
                  return new Preview(
                      current.username(), current.path(), clock.instant().plus(lifetime));
                });
    return preview != null && preview.username().equals(username);
  }

  public record Preview(String username, String path, Instant expiresAt) {}
}
