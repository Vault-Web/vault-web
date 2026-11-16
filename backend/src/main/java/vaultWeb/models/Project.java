package vaultWeb.models;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Project metadata never owns Cloud files or chat history. */
@Entity
@Table(name = "workspace_project")
@Getter
@Setter
@NoArgsConstructor
public class Project {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, length = 2000)
  private String description = "";

  @Column(nullable = false)
  private boolean archived;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  private Long discussionGroupId;
}
