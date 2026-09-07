package vaultWeb.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

  private User createTestUser(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setPassword("hashedPassword123");
    return user;
  }

  private Group createTestGroup(Long id, String name) {
    Group group = new Group();
    group.setId(id);
    group.setName(name);
    group.setDescription("Test group description");
    group.setIsPublic(true);
    return group;
  }

  private Group createTestGroup(Long id, String name, boolean isPublic) {
    Group group = createTestGroup(id, name);
    group.setIsPublic(isPublic);
    return group;
  }

  private GroupDto createTestGroupDto(String name, String description, Boolean isPublic) {
    GroupDto dto = new GroupDto();
    dto.setName(name);
    dto.setDescription(description);
    dto.setIsPublic(isPublic);
    return dto;
  }

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
  void shouldGetPublicGroupById_ForUnauthenticatedCaller() {
    Group group = createTestGroup(1L, "Group 1", true);
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));

    ResponseEntity<GroupResponseDto> response = groupController.getGroupById(1L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1L, response.getBody().getId());
    verify(groupService, times(1)).getGroupById(1L);
  }

  @Test
  void shouldGetPublicGroupById_ForAuthenticatedNonMember() {
    Group group = createTestGroup(1L, "Group 1", true);
    Authentication authentication = mock(Authentication.class);
    User user = createTestUser(5L, "User 5");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));

    ResponseEntity<GroupResponseDto> response = groupController.getGroupById(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1L, response.getBody().getId());
    verify(groupMemberRepository, times(0)).findByGroupIdAndUserId(1L, 5L);
  }

  @Test
  void shouldGetPrivateGroupById_WhenCallerIsMember() {
    Group group = createTestGroup(1L, "Private Group", false);
    Authentication authentication = mock(Authentication.class);
    User user = createTestUser(5L, "Member");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(authService.getCurrentUser()).thenReturn(user);
    when(groupMemberRepository.findByGroupIdAndUserId(1L, 5L))
        .thenReturn(Optional.of(mock(GroupMember.class)));

    ResponseEntity<GroupResponseDto> response = groupController.getGroupById(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1L, response.getBody().getId());
  }

  @Test
  void shouldRejectGetPrivateGroupById_WhenCallerIsNotMember() {
    Group group = createTestGroup(1L, "Private Group", false);
    Authentication authentication = mock(Authentication.class);
    User user = createTestUser(5L, "User 5");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(authService.getCurrentUser()).thenReturn(user);
    when(groupMemberRepository.findByGroupIdAndUserId(1L, 5L)).thenReturn(Optional.empty());

    assertThrows(NotMemberException.class, () -> groupController.getGroupById(1L, authentication));
  }

  @Test
  void shouldRejectGetPrivateGroupById_WhenUnauthenticated() {
    Group group = createTestGroup(1L, "Private Group", false);
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));

    assertThrows(UnauthorizedException.class, () -> groupController.getGroupById(1L, null));
  }

  @Test
  void shouldReturnNotFound_WhenGroupDoesNotExist() {
    when(groupService.getGroupById(999L)).thenReturn(Optional.empty());
    assertThrows(GroupNotFoundException.class, () -> groupController.getGroupById(999L, null));
    verify(groupService, times(1)).getGroupById(999L);
  }

  @Test
  void shouldGetPublicGroupMembers_ForUnauthenticatedCaller() {
    Group group = createTestGroup(1L, "Group 1", true);
    List<User> expectedMembers =
        List.of(createTestUser(1L, "User 1"), createTestUser(2L, "User 2"));
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(groupService.getMembers(1L)).thenReturn(expectedMembers);

    ResponseEntity<List<User>> response = groupController.getGroupMembers(1L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedMembers, response.getBody());
    verify(groupMemberRepository, times(0)).findByGroupIdAndUserId(any(), any());
  }

  @Test
  void shouldGetPublicGroupMembers_ForAuthenticatedNonMember() {
    Group group = createTestGroup(1L, "Group 1", true);
    List<User> expectedMembers = List.of(createTestUser(1L, "User 1"));
    Authentication authentication = mock(Authentication.class);
    User currentUser = createTestUser(5L, "User 5");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(groupService.getMembers(1L)).thenReturn(expectedMembers);

    ResponseEntity<List<User>> response = groupController.getGroupMembers(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedMembers, response.getBody());
    verify(groupMemberRepository, times(0)).findByGroupIdAndUserId(1L, 5L);
  }

  @Test
  void shouldGetPrivateGroupMembers_WhenCallerIsMember() {
    Group group = createTestGroup(1L, "Private Group", false);
    List<User> expectedMembers = List.of(createTestUser(1L, "Member"));
    Authentication authentication = mock(Authentication.class);
    User currentUser = createTestUser(5L, "User 5");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(groupService.getMembers(1L)).thenReturn(expectedMembers);
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(1L, 5L))
        .thenReturn(Optional.of(mock(GroupMember.class)));

    ResponseEntity<List<User>> response = groupController.getGroupMembers(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(expectedMembers, response.getBody());
  }

  @Test
  void shouldRejectGetPrivateGroupMembers_WhenCallerIsNotMember() {
    Group group = createTestGroup(1L, "Private Group", false);
    Authentication authentication = mock(Authentication.class);
    User currentUser = createTestUser(5L, "User 5");
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));
    when(authService.getCurrentUser()).thenReturn(currentUser);
    when(groupMemberRepository.findByGroupIdAndUserId(1L, 5L)).thenReturn(Optional.empty());

    assertThrows(
        NotMemberException.class, () -> groupController.getGroupMembers(1L, authentication));
  }

  @Test
  void shouldRejectGetPrivateGroupMembers_WhenUnauthenticated() {
    Group group = createTestGroup(1L, "Private Group", false);
    when(groupService.getGroupById(1L)).thenReturn(Optional.of(group));

    assertThrows(UnauthorizedException.class, () -> groupController.getGroupMembers(1L, null));
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
        .thenReturn(Optional.of(mock(GroupMember.class)));
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
        .thenReturn(Optional.of(mock(GroupMember.class)));
    when(chatMessageRepository.findByGroupIdOrderByTimestampAsc(10L)).thenReturn(List.of(message));

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
    verify(chatMessageRepository, times(0)).findByGroupIdOrderByTimestampAsc(any());
  }

  @Test
  void shouldRejectGetGroupMessages_WhenUnauthenticated() {
    assertThrows(UnauthorizedException.class, () -> groupController.getGroupMessages(10L, null));
    verify(chatMessageRepository, times(0)).findByGroupIdOrderByTimestampAsc(any());
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
}
