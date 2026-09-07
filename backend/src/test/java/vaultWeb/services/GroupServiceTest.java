package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vaultWeb.dtos.GroupDto;
import vaultWeb.exceptions.AlreadyMemberException;
import vaultWeb.exceptions.LastAdminException;
import vaultWeb.exceptions.PrivateGroupJoinException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.User;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.UserRepository;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

  @Mock private GroupRepository groupRepository;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private UserRepository userRepository;

  @InjectMocks private GroupService groupService;

  private User createUser(Long id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  private Group createGroup(Long id, boolean isPublic) {
    Group group = new Group();
    group.setId(id);
    group.setIsPublic(isPublic);
    return group;
  }

  @Test
  void shouldCreateGroupAndAssignAdmin() {
    User creator = createUser(1L);
    GroupDto dto = new GroupDto("Test Group", "Test Description", true);

    when(groupRepository.save(any(Group.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Group group = groupService.createGroup(dto, creator);

    assertEquals(creator, group.getCreatedBy());
    verify(groupRepository, times(1)).save(any(Group.class));
    verify(groupMemberRepository, times(1)).save(any(GroupMember.class));
  }

  @Test
  void shouldJoinGroupSuccessfully() {
    User user = createUser(2L);
    Group group = createGroup(10L, true);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.empty());

    Group result = groupService.joinGroup(10L, user);

    assertEquals(group, result);
    verify(groupMemberRepository, times(1)).save(any(GroupMember.class));
  }

  @Test
  void shouldFailJoinGroup_WhenPrivateAndNotMember() {
    User user = createUser(2L);
    Group group = createGroup(10L, false);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.empty());

    assertThrows(PrivateGroupJoinException.class, () -> groupService.joinGroup(10L, user));
    verify(groupMemberRepository, never()).save(any(GroupMember.class));
  }

  @Test
  void shouldFailJoinGroup_WhenGroupNotFound() {
    User user = createUser(2L);
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.joinGroup(999L, user));
  }

  @Test
  void shouldFailJoinGroup_WhenAlreadyMember() {
    User user = createUser(2L);
    Group group = createGroup(10L, true);
    GroupMember existingMember = new GroupMember(group, user, Role.USER);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findByGroupAndUser(group, user))
        .thenReturn(Optional.of(existingMember));

    assertThrows(AlreadyMemberException.class, () -> groupService.joinGroup(10L, user));
  }

  @Test
  void shouldLeaveGroupSuccessfully() {
    User user = createUser(2L);
    Group group = createGroup(10L, true);
    GroupMember member = new GroupMember(group, user, Role.USER);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.of(member));

    Group result = groupService.leaveGroup(10L, user);

    assertEquals(group, result);
    verify(groupMemberRepository, times(1)).delete(member);
  }

  @Test
  void shouldFailLeaveGroup_WhenGroupNotFound() {
    User user = createUser(2L);
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.leaveGroup(999L, user));
  }

  @Test
  void shouldFailLeaveGroup_WhenNotMember() {
    User user = createUser(2L);
    Group group = createGroup(10L, true);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.empty());

    assertThrows(NotMemberException.class, () -> groupService.leaveGroup(10L, user));
  }

  @Test
  void shouldRemoveMemberSuccessfully_WhenMemberIsUser() {
    Group group = createGroup(1L, true);
    User user = createUser(2L);
    GroupMember member = new GroupMember(group, user, Role.USER);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.of(member));

    Group result = groupService.removeMember(1L, 2L);

    assertEquals(group, result);
    verify(groupMemberRepository, times(1)).delete(member);
  }

  @Test
  void shouldRemoveMemberSuccessfully_WhenAdminButMultipleAdminsExist() {
    Group group = createGroup(1L, true);
    User admin1 = createUser(1L);
    GroupMember admin1Member = new GroupMember(group, admin1, Role.ADMIN);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(userRepository.findById(1L)).thenReturn(Optional.of(admin1));
    when(groupMemberRepository.findByGroupAndUser(group, admin1))
        .thenReturn(Optional.of(admin1Member));
    when(groupMemberRepository.countByGroupAndRole(group, Role.ADMIN)).thenReturn(2L);

    Group result = groupService.removeMember(1L, 1L);

    assertEquals(group, result);
    verify(groupMemberRepository, times(1)).delete(admin1Member);
  }

  @Test
  void shouldFailRemoveMember_WhenLastAdmin() {
    Group group = createGroup(1L, true);
    User admin = createUser(1L);
    GroupMember member = new GroupMember(group, admin, Role.ADMIN);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
    when(groupMemberRepository.findByGroupAndUser(group, admin)).thenReturn(Optional.of(member));
    when(groupMemberRepository.countByGroupAndRole(group, Role.ADMIN)).thenReturn(1L);

    assertThrows(LastAdminException.class, () -> groupService.removeMember(1L, 1L));
  }

  @Test
  void shouldFailRemoveMember_WhenGroupNotFound() {
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.removeMember(999L, 1L));
  }

  @Test
  void shouldFailRemoveMember_WhenUserNotFound() {
    Group group = createGroup(1L, true);
    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(userRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> groupService.removeMember(1L, 999L));
  }

  @Test
  void shouldFailRemoveMember_WhenUserNotMember() {
    Group group = createGroup(1L, true);
    User user = createUser(2L);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.empty());

    assertThrows(NotMemberException.class, () -> groupService.removeMember(1L, 2L));
  }

  @Test
  void shouldGetMembersSuccessfully() {
    Group group = createGroup(1L, true);
    User user1 = createUser(1L);
    User user2 = createUser(2L);
    GroupMember member1 = new GroupMember(group, user1, Role.ADMIN);
    GroupMember member2 = new GroupMember(group, user2, Role.USER);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(groupMemberRepository.findAllByGroup(group)).thenReturn(List.of(member1, member2));

    List<User> members = groupService.getMembers(1L);

    assertEquals(2, members.size());
    assertTrue(members.contains(user1));
    assertTrue(members.contains(user2));
  }

  @Test
  void shouldFailGetMembers_WhenGroupNotFound() {
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.getMembers(999L));
  }

  @Test
  void shouldUpdateGroupSuccessfully() {
    Group group = createGroup(1L, true);
    GroupDto updatedDto = new GroupDto("Updated Name", "Updated Description", false);

    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(groupRepository.save(any(Group.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Group result = groupService.updateGroup(1L, updatedDto);

    assertEquals("Updated Name", result.getName());
    assertEquals("Updated Description", result.getDescription());
    assertFalse(result.getIsPublic());
    verify(groupRepository, times(1)).save(group);
  }

  @Test
  void shouldFailUpdateGroup_WhenGroupNotFound() {
    GroupDto dto = new GroupDto("New Name", "New Desc", true);
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.updateGroup(999L, dto));
  }

  @Test
  void shouldGetOnlyPublicGroups() {
    Group publicGroup1 = createGroup(1L, true);
    Group publicGroup2 = createGroup(2L, true);
    Group privateGroup = createGroup(3L, false);

    when(groupRepository.findAll()).thenReturn(List.of(publicGroup1, publicGroup2, privateGroup));

    List<Group> publicGroups = groupService.getPublicGroups();

    assertEquals(2, publicGroups.size());
    assertTrue(publicGroups.contains(publicGroup1));
    assertTrue(publicGroups.contains(publicGroup2));
    assertFalse(publicGroups.contains(privateGroup));
  }

  @Test
  void shouldGetGroupById() {
    Group group = createGroup(1L, true);
    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

    Optional<Group> result = groupService.getGroupById(1L);

    assertTrue(result.isPresent());
    assertEquals(group, result.get());
  }

  @Test
  void shouldReturnEmptyOptional_WhenGroupNotFound() {
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    Optional<Group> result = groupService.getGroupById(999L);

    assertFalse(result.isPresent());
  }

  @Test
  void shouldDeleteGroup() {
    Long groupId = 1L;

    groupService.deleteGroup(groupId);

    verify(groupRepository, times(1)).deleteById(groupId);
  }

  @Test
  void shouldGetUserGroups() {
    User user = createUser(2L);
    Group group1 = createGroup(10L, true);
    Group group2 = createGroup(11L, false);
    GroupMember membership1 = new GroupMember(group1, user, Role.USER);
    GroupMember membership2 = new GroupMember(group2, user, Role.ADMIN);

    when(groupMemberRepository.findAllByUser(user)).thenReturn(List.of(membership1, membership2));

    List<Group> result = groupService.getUserGroups(user);

    assertEquals(2, result.size());
    assertEquals(group1, result.get(0));
    assertEquals(group2, result.get(1));
  }

  @Test
  void shouldReturnEmptyList_WhenUserHasNoGroups() {
    User user = createUser(2L);
    when(groupMemberRepository.findAllByUser(user)).thenReturn(List.of());

    List<Group> result = groupService.getUserGroups(user);

    assertTrue(result.isEmpty());
  }

  @Test
  void shouldAddMemberSuccessfully() {
    Group group = createGroup(10L, true);
    User user = createUser(2L);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(groupMemberRepository.findByGroupAndUser(group, user)).thenReturn(Optional.empty());

    Group result = groupService.addMember(10L, 2L);

    assertEquals(group, result);
    verify(groupMemberRepository, times(1)).save(any(GroupMember.class));
  }

  @Test
  void shouldFailAddMember_WhenGroupNotFound() {
    when(groupRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(GroupNotFoundException.class, () -> groupService.addMember(999L, 2L));
    verify(groupMemberRepository, times(0)).save(any(GroupMember.class));
  }

  @Test
  void shouldFailAddMember_WhenUserNotFound() {
    Group group = createGroup(10L, true);
    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(userRepository.findById(999L)).thenReturn(Optional.empty());

    assertThrows(UserNotFoundException.class, () -> groupService.addMember(10L, 999L));
    verify(groupMemberRepository, times(0)).save(any(GroupMember.class));
  }

  @Test
  void shouldFailAddMember_WhenAlreadyMember() {
    Group group = createGroup(10L, true);
    User user = createUser(2L);
    GroupMember existingMember = new GroupMember(group, user, Role.USER);

    when(groupRepository.findById(10L)).thenReturn(Optional.of(group));
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(groupMemberRepository.findByGroupAndUser(group, user))
        .thenReturn(Optional.of(existingMember));

    assertThrows(AlreadyMemberException.class, () -> groupService.addMember(10L, 2L));
    verify(groupMemberRepository, times(0)).save(any(GroupMember.class));
  }
}
