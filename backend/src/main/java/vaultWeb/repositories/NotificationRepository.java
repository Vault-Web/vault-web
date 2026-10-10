package vaultWeb.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vaultWeb.models.Notification;
import vaultWeb.models.User;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
  List<Notification> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
  List<Notification> findByUserAndSourceOrderByCreatedAtDesc(User user, String source, Pageable pageable);
  List<Notification> findByUserAndReadAtIsNullOrderByCreatedAtDesc(User user, Pageable pageable);
  List<Notification> findByUserAndSourceAndReadAtIsNullOrderByCreatedAtDesc(User user, String source, Pageable pageable);
  Optional<Notification> findByIdAndUser(Long id, User user);
  long countByUserAndReadAtIsNull(User user);

  @Modifying
  @Query("update Notification n set n.readAt = :readAt where n.user = :user and n.readAt is null")
  int markAllRead(@Param("user") User user, @Param("readAt") Instant readAt);
}
