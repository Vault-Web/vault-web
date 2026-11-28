package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MediaPreviewServiceTest {
  @Test
  void aShortPreviewCanBeRenewedOnlyForItsOriginalOwnerAndFile() {
    Clock clock = mock(Clock.class);
    Instant initial = Instant.parse("2026-01-01T00:00:00Z");
    when(clock.instant()).thenReturn(initial);
    var previews = new MediaPreviewService(Duration.ofSeconds(2), clock);
    String id = previews.create("alice", "notes.pdf");
    assertEquals("notes.pdf", previews.activePreview(id).path());

    when(clock.instant()).thenReturn(initial.plusSeconds(3));
    assertNull(previews.activePreview(id));
    assertFalse(previews.renew(id, "bob"));
    assertNull(previews.activePreview(id));

    assertTrue(previews.renew(id, "alice"));
    var renewed = previews.activePreview(id);
    assertEquals("notes.pdf", renewed.path());
    assertEquals("alice", renewed.username());
    assertEquals(initial.plusSeconds(5), renewed.expiresAt());
  }
}
