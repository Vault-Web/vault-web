package vaultWeb.dtos;

import java.time.Instant;
import java.util.List;
import vaultWeb.models.ProjectMember.Role;

public record ProjectDto(
    Long id,
    String name,
    String description,
    boolean archived,
    Instant createdAt,
    Role role,
    Long discussionGroupId,
    List<Member> members,
    List<Resource> resources,
    List<Activity> activity) {
  public record Member(Long userId, String username, Role role) {}

  /** Inaccessible references expose no owner/path/share identifiers. */
  public record Resource(
      Long id,
      boolean available,
      String ownerUsername,
      String path,
      Boolean folder,
      String shareId) {}

  public record Activity(String event, String actor, Instant createdAt) {}

  public record Update(
      String name,
      String description,
      Boolean archived,
      Long discussionGroupId,
      Boolean clearDiscussion) {
    public Update(String name, String description, Boolean archived, Long discussionGroupId) {
      this(name, description, archived, discussionGroupId, null);
    }
  }

  public record Membership(String username, Role role) {}

  public record Link(String path, boolean folder) {}
}
