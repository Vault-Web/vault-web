package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.dtos.GroupDto;
import vaultWeb.dtos.GroupResponseDto;
import vaultWeb.exceptions.AlreadyMemberException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Device;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.User;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.DeviceRepository;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.services.GroupService;
import vaultWeb.services.auth.AuthService;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

  @Mock private GroupService groupService;

  @Mock private AuthService authService;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private DeviceRepository deviceRepository;
  @Mock private ChatMessageRepository chatMessageRepository;

  @InjectMocks private GroupController groupController;

  // ============================================================================
  // Test Data Helper Methods
  // ============================================================================

  /**
   * Creates a test User object with the given ID and username.
   *
   * @param id the user ID
   * @param username the username
   * @return a User object for testing
   */
  private User createTestUser(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setPassword("hashedPassword123");
    return user;
  }

  /**
   * Creates a test Group object with the given ID and name.
   *
   * @param id the group ID
   * @param name the group name
   * @return a Group object for testing
   */
  private Group createTestGroup(Long id, String name) {
    Group group = new Group();
    group.setId(id);
    group.setName(name);
    group.setDescription("Test group description");
    group.setIsPublic(true);
    return group;
  }

  /**
   * Creates a test GroupDto object.
   *
   * @param name the group name
   * @param description the description
   * @param isPublic whether the group is public
   * @return a GroupDto for testing
   */
  private GroupDto createTestGroupDto(String name, String description, Boolean isPublic) {
    GroupDto dto = new GroupDto();
    dto.setName(name);
    dto.setDescription(description);
    dto.setIsPublic(isPublic);
    return dto;
  }

  // ============================================================================
  // Happy Path Tests (10 tests)
  // ============================================================================

  @Test
  void shouldGetPublicGroupsSuccessfully() {
    Group group1 = createTestGroup(1L, "Group 1");
    Group group2 = createTestGroup(2L, "Group 2");
    when(groupService.getPublicGroups()).thenReturn(List.of(group1, group2));
    ResponseEntity<List<Group>> response = groupController.getGroups();
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(group1, group2), response.getBody());
    verify(groupService, times(1)).getPublicGroups();
  }

  @Test
  void shouldGetGroupByIdSuccessfully() {
    Group group = createTestGroup(1L, "Group 1");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    ResponseEntity<GroupResponseDto> response = groupController.getGroupById(1L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1L, response.getBody().getId());
    assertEquals("Group 1", response.getBody().getName());
    verify(groupService, times(1)).getGroupById(1L);
  }

  @Test
  void shouldReturnNotFound_WhenGroupDoesNotExist() {
    when(groupService.getGroupById(999L)).thenReturn(Optional.empty());
    ResponseEntity<GroupResponseDto> response = groupController.getGroupById(999L);
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    verify(groupService, times(1)).getGroupById(999L);
  }

  @Test
  void shouldGetGroupMembersSuccessfully() {
    List<User> expectedMembers =
        List.of(createTestUser(1L, "User 1"), createTestUser(2L, "User 2"));
    when(groupService.getMembers(1L)).thenReturn(expectedMembers);
    ResponseEntity<List<User>> response = groupController.getGroupMembers(1L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedMembers, response.getBody());
    verify(groupService, times(1)).getMembers(1L);
  }

  @Test
  void shouldCreateGroupSuccessfully() {
    User testUser = createTestUser(1L, "User 1");
    GroupDto testGroupDto = createTestGroupDto("Group 1", "Group 1 description", true);
    Group expectedGroup = createTestGroup(1L, "Group 1");
    when(authService.getCurrentUser()).thenReturn(testUser);
    when(groupService.createGroup(testGroupDto, testUser)).thenReturn(expectedGroup);
    ResponseEntity<Group> response = groupController.createGroup(testGroupDto);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedGroup, response.getBody());
    verify(authService, times(1)).getCurrentUser();
    verify(groupService, times(1)).createGroup(testGroupDto, testUser);
  }

  @Test
  void shouldJoinGroupSuccessfully() {
    User testUser = createTestUser(1L, "User 1");
    Group expectedGroup = createTestGroup(1L, "Group 1");
    when(authService.getCurrentUser()).thenReturn(testUser);
    when(groupService.joinGroup(1L, testUser)).thenReturn(expectedGroup);
    ResponseEntity<Group> response = groupController.joinGroup(1L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedGroup, response.getBody());
    verify(authService, times(1)).getCurrentUser();
    verify(groupService, times(1)).joinGroup(1L, testUser);
  }

  @Test
  void shouldUpdateGroupSuccessfully() {
    GroupDto testGroupDto = createTestGroupDto("Group 1", "Group 1 description", true);
    Group expectedGroup = createTestGroup(1L, "Group 1");
    when(groupService.updateGroup(1L, testGroupDto)).thenReturn(expectedGroup);
    ResponseEntity<Group> response = groupController.updateGroup(1L, testGroupDto);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedGroup, response.getBody());
    verify(groupService, times(1)).updateGroup(1L, testGroupDto);
  }

  @Test
  void shouldDeleteGroupSuccessfully() {
    doNothing().when(groupService).deleteGroup(1L);
    ResponseEntity<Void> response = groupController.deleteGroup(1L);
    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(groupService, times(1)).deleteGroup(1L);
  }

  @Test
  void shouldLeaveGroupSuccessfully() {
    User testUser = createTestUser(1L, "User 1");
    Group expectedGroup = createTestGroup(1L, "Group 1");
    when(authService.getCurrentUser()).thenReturn(testUser);
    when(groupService.leaveGroup(1L, testUser)).thenReturn(expectedGroup);
    ResponseEntity<Group> response = groupController.leaveGroup(1L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedGroup, response.getBody());
    verify(authService, times(1)).getCurrentUser();
    verify(groupService, times(1)).leaveGroup(1L, testUser);
  }

  @Test
  void shouldRemoveMemberFromGroupSuccessfully() {
    Group expectedGroup = createTestGroup(1L, "Group 1");
    when(groupService.removeMember(1L, 2L)).thenReturn(expectedGroup);
    ResponseEntity<Group> response = groupController.removeMemberFromGroup(1L, 2L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedGroup, response.getBody());
    verify(groupService, times(1)).removeMember(1L, 2L);
  }

  // ============================================================================
  // Error Path Tests (7 tests)
  // ============================================================================

  @Test
  void shouldHandleEmptyGroupList() {

    when(groupService.getPublicGroups()).thenReturn(List.of());
    ResponseEntity<List<Group>> response = groupController.getGroups();
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(), response.getBody());
    verify(groupService, times(1)).getPublicGroups();
  }

  @Test
  void shouldFailJoinGroup_WhenAlreadyMember() {
    User testUser = createTestUser(1L, "User 1");
    when(authService.getCurrentUser()).thenReturn(testUser);
    when(groupService.joinGroup(1L, testUser)).thenThrow(new AlreadyMemberException(1L, 1L));
    assertThrows(AlreadyMemberException.class, () -> groupController.joinGroup(1L));
    verify(authService, times(1)).getCurrentUser();
    verify(groupService, times(1)).joinGroup(1L, testUser);
  }

  @Test
  void shouldFailLeaveGroup_WhenNotMember() {
    User testUser = createTestUser(1L, "User 1");
    when(authService.getCurrentUser()).thenReturn(testUser);
    when(groupService.leaveGroup(1L, testUser)).thenThrow(new NotMemberException(1L, 1L));
    assertThrows(NotMemberException.class, () -> groupController.leaveGroup(1L));
    verify(authService, times(1)).getCurrentUser();
    verify(groupService, times(1)).leaveGroup(1L, testUser);
  }

  @Test
  void shouldFailCreateGroup_WhenUserNotAuthenticated() {
    GroupDto testGroupDto = createTestGroupDto("Group 1", "Group 1 description", true);
    when(authService.getCurrentUser()).thenReturn(null);
    when(groupService.createGroup(any(GroupDto.class), eq(null)))
        .thenThrow(new NullPointerException());
    assertThrows(NullPointerException.class, () -> groupController.createGroup(testGroupDto));
    verify(authService, times(1)).getCurrentUser();
  }

  @Test
  void shouldHandleEmptyMemberList() {
    when(groupService.getMembers(1L)).thenReturn(List.of());
    ResponseEntity<List<User>> response = groupController.getGroupMembers(1L);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(), response.getBody());
    verify(groupService, times(1)).getMembers(1L);
  }

  @Test
  void shouldFailGetMembers_WhenGroupNotFound() {
    when(groupService.getMembers(999L))
        .thenThrow(new GroupNotFoundException("Group not found with id: 999"));
    assertThrows(GroupNotFoundException.class, () -> groupController.getGroupMembers(999L));
    verify(groupService, times(1)).getMembers(999L);
  }

  @Test
  void shouldFailUpdateGroup_WhenGroupNotFound() {
    GroupDto testGroupDto = createTestGroupDto("Group 1", "Group 1 description", true);
    when(groupService.updateGroup(999L, testGroupDto))
        .thenThrow(new GroupNotFoundException("Group not found with id: 999"));
    assertThrows(
        GroupNotFoundException.class, () -> groupController.updateGroup(999L, testGroupDto));
    verify(groupService, times(1)).updateGroup(999L, testGroupDto);
  }

  @Test
  void shouldGetGroupDevices_WhenUserIsMember() {
    User currentUser = createTestUser(1L, "member");
    User member1 = createTestUser(1L, "member");
    User member2 = createTestUser(2L, "member2");
    Device device1 = new Device();
    device1.setDeviceId("dev-1");
    device1.setPublicKey("pk-1");
    device1.setUser(member1);
    Device device2 = new Device();
    device2.setDeviceId("dev-2");
    device2.setPublicKey("pk-2");
    device2.setUser(member2);

    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(10L, 1L))
        .thenReturn(Optional.of(mock(vaultWeb.models.GroupMember.class)));
    when(groupService.getMembers(10L)).thenReturn(List.of(member1, member2));
    when(deviceRepository.findByUserIn(List.of(member1, member2)))
        .thenReturn(List.of(device1, device2));

    ResponseEntity<List<DeviceDto>> response = groupController.getGroupDevices(10L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(2, response.getBody().size());
    assertEquals("dev-1", response.getBody().get(0).getDeviceId());
    verify(deviceRepository).findByUserIn(List.of(member1, member2));
  }

  @Test
  void shouldRejectGetGroupDevices_WhenUserIsNotMember() {
    User currentUser = createTestUser(1L, "member");
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(10L, 1L)).thenReturn(Optional.empty());

    assertThrows(NotMemberException.class, () -> groupController.getGroupDevices(10L));
    verify(deviceRepository, times(0)).findByUserIn(any());
  }

  @Test
  void shouldRejectGetGroupDevices_WhenUserIsUnauthenticated() {
    when(authService.getCurrentUser()).thenReturn(null);

    assertThrows(UnauthorizedException.class, () -> groupController.getGroupDevices(10L));
    verify(groupMemberRepository, times(0)).findByGroupIdAndUserId(any(), any());
    verify(deviceRepository, times(0)).findByUserIn(any());
  }

  @Test
  void shouldGetGroupMessages_WhenUserIsMember() {
    User currentUser = createTestUser(1L, "member");
    User sender = createTestUser(2L, "sender");
    ChatMessage message = new ChatMessage();
    message.setSender(sender);
    message.setSenderDeviceId("sender-device");
    message.setE2eePayload("{\"v\":2}");
    message.setTimestamp(java.time.Instant.parse("2026-03-26T10:15:30Z"));
    Authentication authentication = mock(Authentication.class);

    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(10L, 1L))
        .thenReturn(Optional.of(mock(vaultWeb.models.GroupMember.class)));
    when(chatMessageRepository.findByGroupIdAndDeletedFalseOrderByTimestampAsc(10L))
        .thenReturn(List.of(message));

    ResponseEntity<List<ChatMessageDto>> response =
        groupController.getGroupMessages(10L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
    assertEquals("{\"v\":2}", response.getBody().get(0).getE2eePayload());
    assertEquals(10L, response.getBody().get(0).getGroupId());
    assertEquals("sender", response.getBody().get(0).getSenderUsername());
  }

  @Test
  void shouldRejectGetGroupMessages_WhenUserIsNotMember() {
    User currentUser = createTestUser(1L, "member");
    Authentication authentication = mock(Authentication.class);

    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(10L, 1L)).thenReturn(Optional.empty());

    assertThrows(
        NotMemberException.class, () -> groupController.getGroupMessages(10L, authentication));
    verify(chatMessageRepository, times(0)).findByGroupIdAndDeletedFalseOrderByTimestampAsc(any());
  }

  @Test
  void shouldRejectGetGroupMessages_WhenUnauthenticated() {
    assertThrows(UnauthorizedException.class, () -> groupController.getGroupMessages(10L, null));
    verify(chatMessageRepository, times(0)).findByGroupIdAndDeletedFalseOrderByTimestampAsc(any());
  }

  @Test
  void shouldGetMyGroups_AsDtoWithoutLeakingUserEntity() {
    User currentUser = createTestUser(1L, "member");
    User otherMember = createTestUser(2L, "friend");
    Group group = createTestGroup(10L, "My Group");
    GroupMember membership = new GroupMember(group, otherMember, Role.USER);
    membership.setId(100L);
    group.setMembers(List.of(membership));

    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupService.getUserGroups(currentUser)).thenReturn(List.of(group));

    ResponseEntity<List<GroupResponseDto>> response = groupController.getMyGroups();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
    GroupResponseDto dto = response.getBody().get(0);
    assertEquals(10L, dto.getId());
    assertEquals("My Group", dto.getName());
    assertEquals(1, dto.getMembers().size());
    // The response carries only the minimal user projection, never the User entity/password.
    assertEquals(2L, dto.getMembers().get(0).getUser().getId());
    assertEquals("friend", dto.getMembers().get(0).getUser().getUsername());
    assertEquals("USER", dto.getMembers().get(0).getRole());
  }

  @Test
  void shouldRejectGetMyGroups_WhenUnauthenticated() {
    when(authService.getCurrentUser()).thenReturn(null);

    assertThrows(UnauthorizedException.class, () -> groupController.getMyGroups());
    verify(groupService, times(0)).getUserGroups(any());
  }

  @Test
  void shouldAddMemberToGroup_ReturningDto() {
    Group group = createTestGroup(10L, "My Group");
    group.setMembers(List.of());

    when(groupService.addMember(10L, 2L)).thenReturn(group);

    ResponseEntity<GroupResponseDto> response = groupController.addMemberToGroup(10L, 2L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(10L, response.getBody().getId());
    verify(groupService, times(1)).addMember(10L, 2L);
  }
}
