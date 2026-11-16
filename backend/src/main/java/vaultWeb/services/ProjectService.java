package vaultWeb.services;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.dtos.ProjectDto;
import vaultWeb.exceptions.ProjectConflictException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.*;
import vaultWeb.models.ProjectMember.Role;
import vaultWeb.repositories.*;
import vaultWeb.services.auth.AuthService;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {
  private final ProjectRepository projects;
  private final ProjectMemberRepository members;
  private final ProjectResourceRepository resources;
  private final ProjectActivityRepository activity;
  private final UserRepository users;
  private final GroupMemberRepository groupMembers;
  private final AuthService auth;
  private final ProjectResourceResolver resolver;

  public List<ProjectDto> list() {
    User caller = caller();
    return projects.findForUser(caller.getId()).stream()
        .map(
            p ->
                new ProjectDto(
                    p.getId(),
                    p.getName(),
                    p.getDescription(),
                    p.isArchived(),
                    p.getCreatedAt(),
                    membership(p, caller).getRole(),
                    null,
                    List.of(),
                    List.of(),
                    List.of()))
        .toList();
  }

  public Long create(ProjectDto.Update input) {
    User caller = caller();
    Project project = new Project();
    metadata(project, input, caller);
    projects.save(project);
    ProjectMember owner = new ProjectMember();
    owner.setProject(project);
    owner.setUser(caller);
    owner.setRole(Role.OWNER);
    members.save(owner);
    event(project, caller, "PROJECT_CREATED");
    return project.getId();
  }

  @Transactional(readOnly = true)
  public ProjectDto overview(Long id, String authorization) {
    User caller = caller();
    Project project =
        projects.findById(id).orElseThrow(() -> new AccessDeniedException("Project unavailable"));
    Role role = membership(project, caller).getRole();
    Long discussion = project.getDiscussionGroupId();
    if (discussion != null
        && groupMembers.findByGroupIdAndUserId(discussion, caller.getId()).isEmpty())
      discussion = null;
    return new ProjectDto(
        project.getId(),
        project.getName(),
        project.getDescription(),
        project.isArchived(),
        project.getCreatedAt(),
        role,
        discussion,
        members.findByProjectIdOrderByIdAsc(id).stream()
            .map(
                m ->
                    new ProjectDto.Member(
                        m.getUser().getId(), m.getUser().getUsername(), m.getRole()))
            .toList(),
        resources.findByProjectIdOrderByIdAsc(id).stream()
            .map(r -> resolver.resolve(r, caller, authorization))
            .toList(),
        activity.findTop50ByProjectIdOrderByIdDesc(id).stream()
            .map(a -> new ProjectDto.Activity(a.getEvent(), a.getActor(), a.getCreatedAt()))
            .toList());
  }

  public void update(Long id, ProjectDto.Update input) {
    User caller = caller();
    Project p = locked(id, caller);
    requireAdmin(membership(p, caller));
    Long previousDiscussion = p.getDiscussionGroupId();
    if (input.archived() != null) {
      p.setArchived(input.archived());
      event(p, caller, p.isArchived() ? "PROJECT_ARCHIVED" : "PROJECT_REOPENED");
    }
    if (input.name() != null) {
      active(p);
      metadata(p, input, caller);
      event(p, caller, "PROJECT_UPDATED");
      if (!java.util.Objects.equals(previousDiscussion, p.getDiscussionGroupId()))
        event(p, caller, "DISCUSSION_LINK_CHANGED");
    }
  }

  public void member(Long id, ProjectDto.Membership input) {
    User caller = caller();
    Project p = locked(id, caller);
    ProjectMember actor = membership(p, caller);
    requireAdmin(actor);
    active(p);
    if (input.username() == null || input.role() == null)
      throw new IllegalArgumentException("Member and role required");
    User user =
        users
            .findByUsername(input.username())
            .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    var existing = members.findByProjectIdAndUserId(id, user.getId());
    if (actor.getRole() != Role.OWNER
        && (input.role() != Role.MEMBER
            || existing.map(m -> m.getRole() != Role.MEMBER).orElse(false))) denied();
    ProjectMember m = existing.orElseGet(ProjectMember::new);
    if (existing.isPresent() && m.getRole() == Role.OWNER && input.role() != Role.OWNER)
      lastOwner(p, m);
    if (existing.isEmpty() && members.findByProjectIdOrderByIdAsc(id).size() >= 100)
      throw new IllegalArgumentException("Member limit reached");
    m.setProject(p);
    m.setUser(user);
    m.setRole(input.role());
    members.save(m);
    event(p, caller, existing.isPresent() ? "MEMBER_ROLE_CHANGED" : "MEMBER_ADDED");
  }

  public void removeMember(Long id, Long userId, boolean leave) {
    User caller = caller();
    Project p = locked(id, caller);
    ProjectMember actor = membership(p, caller);
    Long targetId = leave ? caller.getId() : userId;
    ProjectMember target =
        members
            .findByProjectIdAndUserId(id, targetId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown member"));
    if (!leave) {
      active(p);
      requireAdmin(actor);
      if (actor.getRole() != Role.OWNER && target.getRole() != Role.MEMBER) denied();
    }
    lastOwner(p, target);
    members.delete(target);
    event(p, caller, leave ? "MEMBER_LEFT" : "MEMBER_REMOVED");
  }

  public void link(Long id, ProjectDto.Link input, String authorization) {
    User caller = caller();
    Project p = locked(id, caller);
    requireAdmin(membership(p, caller));
    active(p);
    if (resources.findByProjectIdOrderByIdAsc(id).size() >= 50)
      throw new IllegalArgumentException("Resource limit reached");
    ProjectResourceLink link = new ProjectResourceLink();
    link.setProject(p);
    link.setOwner(caller);
    link.setPath(ProjectResourceResolver.canonical(input.path()));
    link.setFolder(input.folder());
    if (!resolver.resolve(link, caller, authorization).available()) denied();
    resources.save(link);
    event(p, caller, "RESOURCE_LINKED");
  }

  public void unlink(Long id, Long linkId) {
    User caller = caller();
    Project p = locked(id, caller);
    requireAdmin(membership(p, caller));
    active(p);
    ProjectResourceLink link =
        resources
            .findById(linkId)
            .filter(r -> r.getProject().getId().equals(id))
            .orElseThrow(() -> new IllegalArgumentException("Unknown reference"));
    resources.delete(link);
    event(p, caller, "RESOURCE_UNLINKED");
  }

  public void delete(Long id) {
    User caller = caller();
    Project p = locked(id, caller);
    if (membership(p, caller).getRole() != Role.OWNER) denied();
    resources.deleteByProjectId(id);
    activity.deleteByProjectId(id);
    members.deleteByProjectId(id);
    projects.delete(p);
  }

  private User caller() {
    User user = auth.getCurrentUser();
    if (user == null) throw new UnauthorizedException("Authentication required");
    return user;
  }

  private Project locked(Long id, User caller) {
    // Check membership before taking a write lock on private metadata.
    if (!members.existsByProjectIdAndUserId(id, caller.getId())) denied();
    Project p =
        projects.lockById(id).orElseThrow(() -> new AccessDeniedException("Project unavailable"));
    membership(p, caller);
    return p;
  }

  private ProjectMember membership(Project p, User caller) {
    return members
        .findByProjectIdAndUserId(p.getId(), caller.getId())
        .orElseThrow(() -> new AccessDeniedException("Project unavailable"));
  }

  private void requireAdmin(ProjectMember m) {
    if (m.getRole() == Role.MEMBER) denied();
  }

  private void denied() {
    throw new AccessDeniedException("Project operation unavailable");
  }

  private void active(Project p) {
    if (p.isArchived()) throw new ProjectConflictException("Project is archived");
  }

  private void lastOwner(Project p, ProjectMember m) {
    if (m.getRole() == Role.OWNER && members.countByProjectIdAndRole(p.getId(), Role.OWNER) <= 1)
      throw new ProjectConflictException("A project must retain an owner");
  }

  private void metadata(Project p, ProjectDto.Update input, User caller) {
    if (input.name() == null
        || input.name().isBlank()
        || input.name().length() > 120
        || (input.description() != null && input.description().length() > 2000))
      throw new IllegalArgumentException("Invalid project metadata");
    if (input.discussionGroupId() != null
        && groupMembers.findByGroupIdAndUserId(input.discussionGroupId(), caller.getId()).isEmpty())
      denied();
    p.setName(input.name().strip());
    p.setDescription(input.description() == null ? "" : input.description());
    p.setDiscussionGroupId(input.discussionGroupId());
  }

  private void event(Project p, User caller, String name) {
    ProjectActivity a = new ProjectActivity();
    a.setProject(p);
    a.setActor(caller.getUsername());
    a.setEvent(name);
    activity.save(a);
  }
}
