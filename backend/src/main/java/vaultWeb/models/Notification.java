package vaultWeb.models;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "user_notification", indexes = {
    @Index(name = "idx_notification_user_created", columnList = "user_id, created_at"),
    @Index(name = "idx_notification_user_read", columnList = "user_id, read_at")
})
public class Notification {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 24)
  private String source;

  @Column(nullable = false, length = 48)
  private String type;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(nullable = false, length = 320)
  private String message;

  @Column(length = 512)
  private String linkUrl;

  @Column(length = 120)
  private String referenceId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "read_at")
  private Instant readAt;
}
