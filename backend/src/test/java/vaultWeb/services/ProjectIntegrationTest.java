package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vaultWeb.dtos.ProjectDto;
import vaultWeb.exceptions.ProjectConflictException;
import vaultWeb.models.ProjectMember.Role;
import vaultWeb.models.User;
import vaultWeb.repositories.*;
import vaultWeb.services.auth.AuthService;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProjectIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ProjectService service;
  @Autowired ProjectRepository projects;
  @Autowired ProjectMemberRepository members;
  @Autowired ProjectResourceRepository resources;
  @Autowired ProjectActivityRepository activity;
  @Autowired UserRepository users;
  @Autowired GroupRepository groups;
  @Autowired GroupMemberRepository groupMembers;
  @MockitoBean AuthService auth;
  @MockitoBean ProjectResourceResolver resolver;
  private final ThreadLocal<User> current = new ThreadLocal<>();
  private User owner, admin, member, outsider;
  private Long id;

  @BeforeEach
  void setup() {
    activity.deleteAll();
    resources.deleteAll();
    members.deleteAll();
    projects.deleteAll();
    String suffix = java.util.UUID.randomUUID().toString();
    owner = user("owner-" + suffix);
    admin = user("admin-" + suffix);
    member = user("member-" + suffix);
    outsider = user("outsider-" + suffix);
    when(auth.getCurrentUser()).thenAnswer(inv -> current.get());
    current.set(owner);
    id = service.create(new ProjectDto.Update("Thesis", "Private context", null, null));
    when(resolver.resolve(any(), any(), any()))
        .thenAnswer(
            inv -> {
              var r = (vaultWeb.models.ProjectResourceLink) inv.getArgument(0);
              return new ProjectDto.Resource(
                  r.getId(), true, owner.getUsername(), r.getPath(), r.isFolder(), null);
            });
  }

  private User user(String name) {
    User u = new User();
    u.setUsername(name);
    u.setPassword("synthetic-hash");
    return users.save(u);
  }

  @AfterEach
  void clear() {
    current.remove();
  }

  @Test
  void privateMetadataAndMutationsRequireMembership() {
    current.set(outsider);
    assertTrue(service.list().isEmpty());
    assertThrows(AccessDeniedException.class, () -> service.overview(id, "Bearer synthetic"));
    assertThrows(
        AccessDeniedException.class,
        () -> service.update(id, new ProjectDto.Update("stolen", "", null, null)));
    assertThrows(AccessDeniedException.class, () -> service.delete(id));
    verifyNoInteractions(resolver);
  }

  @Test
  @WithMockUser(username = "synthetic")
  void httpEndpointsRejectNonmembersAndNeverSerializePasswords() throws Exception {
    current.set(outsider);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/projects/" + id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/projects/" + id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    current.set(owner);
    var result =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/projects/" + id))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains("password"));
    assertTrue(result.getResponse().getContentAsString().contains(owner.getUsername()));
  }

  @Test
  void discussionReferenceDoesNotGrantIndependentGroupMembership() {
    var group = new vaultWeb.models.Group();
    group.setName("Synthetic discussion");
    group.setIsPublic(false);
    groups.save(group);
    assertThrows(
        AccessDeniedException.class,
        () -> service.update(id, new ProjectDto.Update("Thesis", "", null, group.getId())));
    var membership = new vaultWeb.models.GroupMember();
    membership.setGroup(group);
    membership.setUser(owner);
    membership.setRole(vaultWeb.models.enums.Role.ADMIN);
    groupMembers.save(membership);
    service.update(id, new ProjectDto.Update("Thesis", "", null, group.getId()));
    service.member(id, new ProjectDto.Membership(member.getUsername(), Role.MEMBER));
    service.member(id, new ProjectDto.Membership(admin.getUsername(), Role.ADMIN));
    assertEquals(group.getId(), service.overview(id, "Bearer synthetic").discussionGroupId());
    current.set(member);
    assertNull(service.overview(id, "Bearer synthetic").discussionGroupId());
    assertTrue(groupMembers.findByGroupIdAndUserId(group.getId(), member.getId()).isEmpty());
    current.set(admin);
    assertNull(service.overview(id, "Bearer synthetic").discussionGroupId());
    service.update(id, new ProjectDto.Update("Renamed by non-group admin", "Context", null, null));
    assertEquals(group.getId(), projects.findById(id).orElseThrow().getDiscussionGroupId());
    service.update(id, new ProjectDto.Update(null, null, null, null, true));
    assertNull(projects.findById(id).orElseThrow().getDiscussionGroupId());
    current.set(owner);
    service.delete(id);
    assertTrue(groups.existsById(group.getId()));
  }

  @Test
  void partialUpdatesPreserveOmittedMetadataAndAllowExplicitEmptyDescription() {
    service.update(id, new ProjectDto.Update("New name", null, null, null));
    assertEquals("Private context", service.overview(id, "Bearer synthetic").description());
    service.update(id, new ProjectDto.Update(null, "Description only", null, null));
    assertEquals("New name", service.overview(id, "Bearer synthetic").name());
    assertEquals("Description only", service.overview(id, "Bearer synthetic").description());
    service.update(id, new ProjectDto.Update(null, "", null, null));
    assertEquals("", service.overview(id, "Bearer synthetic").description());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.update(id, new ProjectDto.Update(null, null, null, 123L, true)));
  }

  @Test
  void crudRolesArchiveAndDeletionPreserveExternalResources() {
    service.member(id, new ProjectDto.Membership(admin.getUsername(), Role.ADMIN));
    service.member(id, new ProjectDto.Membership(member.getUsername(), Role.MEMBER));
    current.set(member);
    assertEquals(3, service.overview(id, "Bearer synthetic").members().size());
    assertThrows(
        AccessDeniedException.class,
        () -> service.link(id, new ProjectDto.Link("/note.md", false), "Bearer synthetic"));
    current.set(admin);
    assertThrows(
        AccessDeniedException.class,
        () -> service.member(id, new ProjectDto.Membership(admin.getUsername(), Role.OWNER)));
    assertThrows(AccessDeniedException.class, () -> service.removeMember(id, owner.getId(), false));
    service.update(id, new ProjectDto.Update("Renamed", "Context", null, null));
    service.link(id, new ProjectDto.Link("/note.md", false), "Bearer synthetic");
    assertEquals(1, service.overview(id, "Bearer synthetic").resources().size());
    service.update(id, new ProjectDto.Update(null, null, true, null));
    assertThrows(
        ProjectConflictException.class,
        () -> service.link(id, new ProjectDto.Link("/other.md", false), "Bearer synthetic"));
    current.set(member);
    service.removeMember(id, null, true);
    current.set(owner);
    assertThrows(ProjectConflictException.class, () -> service.removeMember(id, null, true));
    assertTrue(
        service.overview(id, "Bearer synthetic").activity().stream()
            .anyMatch(a -> a.event().equals("RESOURCE_LINKED")));
    service.delete(id);
    assertFalse(projects.existsById(id));
    assertTrue(resources.findByProjectIdOrderByIdAsc(id).isEmpty());
    assertTrue(activity.findTop50ByProjectIdOrderByIdDesc(id).isEmpty());
    assertTrue(users.existsById(owner.getId()));
  }

  @Test
  void concurrentOwnersCannotBothLeave() throws Exception {
    service.member(id, new ProjectDto.Membership(admin.getUsername(), Role.OWNER));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> leaveConcurrent(owner, ready, start));
      var second = pool.submit(() -> leaveConcurrent(admin, ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
      assertEquals(1, members.countByProjectIdAndRole(id, Role.OWNER));
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  private int leaveConcurrent(User user, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    current.set(user);
    ready.countDown();
    start.await();
    try {
      service.removeMember(id, null, true);
      return 1;
    } catch (ProjectConflictException ex) {
      return 0;
    } finally {
      current.remove();
    }
  }

  @Test
  void unavailableCloudReferenceCannotBeLinkedAndForeignLinkCannotBeRemoved() {
    doReturn(new ProjectDto.Resource(null, false, null, null, null, null))
        .when(resolver)
        .resolve(any(), any(), any());
    assertThrows(
        AccessDeniedException.class,
        () -> service.link(id, new ProjectDto.Link("/private.md", false), "Bearer synthetic"));
    assertTrue(resources.findByProjectIdOrderByIdAsc(id).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> service.unlink(id, 9999L));
  }
}
