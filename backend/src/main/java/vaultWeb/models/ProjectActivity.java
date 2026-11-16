package vaultWeb.models;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workspace_activity")
@Getter
@Setter
@NoArgsConstructor
public class ProjectActivity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Column(nullable = false, length = 50)
  private String event;

  @Column(nullable = false, length = 255)
  private String actor;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
