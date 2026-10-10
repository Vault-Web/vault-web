package vaultWeb.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "notification_preference", uniqueConstraints = @UniqueConstraint(
    name = "uk_notification_preference_user_source", columnNames = {"user_id", "source"}))
public class NotificationPreference {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 24)
  private String source;

  @Column(nullable = false)
  private boolean muted;
}
