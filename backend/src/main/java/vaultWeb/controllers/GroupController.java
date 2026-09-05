package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.dtos.DeviceDto;
import vaultWeb.dtos.GroupDto;
import vaultWeb.dtos.GroupResponseDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.DeviceRepository;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.security.annotations.AdminOnly;
import vaultWeb.services.GroupService;
import vaultWeb.services.auth.AuthService;

/**
 * Controller for managing groups within the application.
 *
 * <p>Provides endpoints to list public groups, get details of a group, manage group membership, and
 * create, update, or delete groups. Some operations require the user to have admin privileges.
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group Controller", description = "Manages the different groups a user can join")
public class GroupController {

  private final GroupService groupService;
  private final AuthService authService;
  private final GroupMemberRepository groupMemberRepository;
  private final DeviceRepository deviceRepository;
  private final ChatMessageRepository chatMessageRepository;

  /**
   * Retrieves all public groups.
   *
   * @return a list of public groups
   */
  @GetMapping("")
  @Operation(summary = "Retrieves all public groups.")
  @ApiResponse(responseCode = "200", description = "Public groups retrieved successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<List<Group>> getGroups() {
    List<Group> publicGroups = groupService.getPublicGroups();
    return ResponseEntity.ok(publicGroups);
  }

  /**
   * Retrieves a group by its ID.
   *
   * @param id the ID of the group
   * @return the group if found, or 404 if not found
   */
  @GetMapping("/{id}")
  @Operation(summary = "Retrieves a group by its ID")
  @ApiResponse(responseCode = "200", description = "Group retrieved successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  @ApiResponse(responseCode = "404", description = "Group was not found.")
  public ResponseEntity<GroupResponseDto> getGroupById(@PathVariable Long id) {
    return groupService
        .getGroupById(id)
        .map(GroupResponseDto::from)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Retrieves all members of a given group.
   *
   * @param id the ID of the group
   * @return a list of users in the group
   */
  @GetMapping("/{id}/members")
  @Operation(summary = "Retrieves all members of a given group")
  @ApiResponse(responseCode = "200", description = "Members retrieved successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<List<User>> getGroupMembers(@PathVariable Long id) {
    List<User> members = groupService.getMembers(id);
    return ResponseEntity.ok(members);
  }

  @GetMapping("/{id}/devices")
  public ResponseEntity<List<DeviceDto>> getGroupDevices(@PathVariable Long id) {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    if (groupMemberRepository.findByGroupIdAndUserId(id, currentUser.getId()).isEmpty()) {
      throw new NotMemberException(id, currentUser.getId());
    }
    List<User> members = groupService.getMembers(id);
    List<DeviceDto> devices =
        deviceRepository.findByUserIn(members).stream().map(DeviceDto::from).toList();
    return ResponseEntity.ok(devices);
  }

  @GetMapping("/{id}/messages")
  @Operation(
      summary = "Get all messages of a group chat",
      description =
          """
                    Retrieves all messages from a group chat.
                    - The current user must be a group member.
                    - Messages are ordered chronologically by timestamp.
                    - Message content remains end-to-end encrypted and is never decrypted by the server.
                    """)
  public ResponseEntity<List<ChatMessageDto>> getGroupMessages(
      @PathVariable Long id, Authentication authentication) {
    User currentUser = getAuthenticatedGroupMember(id, authentication);
    List<ChatMessage> messages =
        chatMessageRepository.findByGroupIdAndDeletedFalseOrderByTimestampAsc(id);

    List<ChatMessageDto> response =
        messages.stream()
            .map(
                message -> {
                  ChatMessageDto dto = new ChatMessageDto();
                  dto.setE2eePayload(message.getE2eePayload());
                  dto.setTimestamp(message.getTimestamp().toString());
                  dto.setGroupId(id);
                  dto.setPrivateChatId(null);
                  dto.setSenderId(message.getSender().getId());
                  dto.setSenderUsername(message.getSender().getUsername());
                  dto.setSenderDeviceId(message.getSenderDeviceId());
                  return dto;
                })
            .toList();

    return ResponseEntity.ok(response);
  }

  private User getAuthenticatedGroupMember(Long groupId, Authentication authentication) {
    if (authentication == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    if (groupMemberRepository.findByGroupIdAndUserId(groupId, currentUser.getId()).isEmpty()) {
      throw new NotMemberException(groupId, currentUser.getId());
    }
    return currentUser;
  }

  /**
   * Creates a new group.
   *
   * @param groupDto the data for the new group
   * @return the created group
   */
  @PostMapping("")
  @Operation(summary = "Creates a new group")
  @ApiResponse(responseCode = "200", description = "Group created successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<Group> createGroup(@RequestBody GroupDto groupDto) {
    User currentUser = authService.getCurrentUser();
    Group created = groupService.createGroup(groupDto, currentUser);
    return ResponseEntity.ok(created);
  }

  /**
   * Current user joins a group.
   *
   * @param id the ID of the group to join
   * @return the updated group
   */
  @PostMapping("/{id}/join")
  @Operation(summary = "Join a new group")
  @ApiResponse(responseCode = "200", description = "Group joined successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<Group> joinGroup(@PathVariable Long id) {
    User currentUser = authService.getCurrentUser();
    Group updatedGroup = groupService.joinGroup(id, currentUser);
    return ResponseEntity.ok(updatedGroup);
  }

  /**
   * Updates a group. Admin privileges required.
   *
   * @param id the ID of the group to update
   * @param updatedGroup the updated group data
   * @return the updated group
   */
  @AdminOnly
  @PutMapping("/{id}")
  @Operation(summary = "Updates a group. Admin privileges required")
  @ApiResponse(responseCode = "200", description = "Group updated successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  @ApiResponse(
      responseCode = "403",
      description = "Unauthorized request. You must have admin privileges.")
  public ResponseEntity<Group> updateGroup(
      @PathVariable Long id, @RequestBody GroupDto updatedGroup) {
    return ResponseEntity.ok(groupService.updateGroup(id, updatedGroup));
  }

  /**
   * Deletes a group. Admin privileges required.
   *
   * @param id the ID of the group to delete
   */
  @AdminOnly
  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes a group. Admin privileges required")
  @ApiResponse(responseCode = "200", description = "Group deleted successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  @ApiResponse(
      responseCode = "403",
      description = "Unauthorized request. You must have admin privileges.")
  public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
    groupService.deleteGroup(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Current user leaves a group.
   *
   * @param id the ID of the group to leave
   * @return the updated group
   */
  @DeleteMapping("/{id}/leave")
  @Operation(summary = "Leave a current group")
  @ApiResponse(responseCode = "200", description = "Group left successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<Group> leaveGroup(@PathVariable Long id) {
    User currentUser = authService.getCurrentUser();
    Group updatedGroup = groupService.leaveGroup(id, currentUser);
    return ResponseEntity.ok(updatedGroup);
  }

  /**
   * Removes a member from a group. Admin privileges required.
   *
   * @param groupId the ID of the group
   * @param userId the ID of the user to remove
   * @return the updated group
   */
  @AdminOnly
  @DeleteMapping("/{groupId}/members/{userId}")
  @Operation(summary = "Remove a member from a group. Admin privileges required")
  @ApiResponse(responseCode = "200", description = "Member kicked successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  @ApiResponse(
      responseCode = "403",
      description = "Unauthorized request. You must have admin privileges.")
  public ResponseEntity<Group> removeMemberFromGroup(
      @PathVariable Long groupId, @PathVariable Long userId) {
    Group group = groupService.removeMember(groupId, userId);
    return ResponseEntity.ok(group);
  }

  /**
   * Retrieves all groups the current user is a member of.
   *
   * @return a list of groups
   */
  @GetMapping("/my-groups")
  @Operation(summary = "Retrieves all groups the current user is a member of.")
  @ApiResponse(responseCode = "200", description = "Groups retrieved successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  public ResponseEntity<List<GroupResponseDto>> getMyGroups() {
    User currentUser = authService.getCurrentUser();
    if (currentUser == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    List<GroupResponseDto> userGroups =
        groupService.getUserGroups(currentUser).stream().map(GroupResponseDto::from).toList();
    return ResponseEntity.ok(userGroups);
  }

  /**
   * Adds a member to a group. Admin privileges required.
   *
   * @param groupId the ID of the group
   * @param userId the ID of the user to add
   * @return the updated group
   */
  @AdminOnly
  @PostMapping("/{groupId}/members/{userId}")
  @Operation(summary = "Add a member to a group. Admin privileges required")
  @ApiResponse(responseCode = "200", description = "Member added successfully")
  @ApiResponse(
      responseCode = "401",
      description = "Unauthorized request. You must provide an authentication token.")
  @ApiResponse(
      responseCode = "403",
      description = "Unauthorized request. You must have admin privileges.")
  public ResponseEntity<GroupResponseDto> addMemberToGroup(
      @PathVariable Long groupId, @PathVariable Long userId) {
    Group group = groupService.addMember(groupId, userId);
    return ResponseEntity.ok(GroupResponseDto.from(group));
  }
}
