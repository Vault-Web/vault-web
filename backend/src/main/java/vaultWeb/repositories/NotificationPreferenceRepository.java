package vaultWeb.repositories;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vaultWeb.models.NotificationPreference;
import vaultWeb.models.User;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
  List<NotificationPreference> findByUserOrderBySourceAsc(User user);
  Optional<NotificationPreference> findByUserAndSource(User user, String source);
}
