package vaultWeb.services.auth;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vaultWeb.repositories.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

  private final RefreshTokenRepository refreshTokenRepository;

  @Value("${refresh.cleanup.days}")
  private long cleanupDays;

  static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RefreshTokenCleanupService.class);

  @Scheduled(cron = "0 0 3 * * ?") // every day at 3 AM
  @Transactional
  public void cleanup() {
    if (cleanupDays <= 0) {
      log.warn("Refresh token cleanup skipped: cleanupDays={}", cleanupDays);
      return;
    }

    Instant now = Instant.now();
    Instant cutoff = now.minus(cleanupDays, ChronoUnit.DAYS);

    int deleted = refreshTokenRepository.deleteExpiredAndOldRevoked(now, cutoff);
    log.info("RefreshToken cleanup ran. Deleted {} tokens.", deleted);
  }
}
