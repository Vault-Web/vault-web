package vaultWeb.services;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

/**
 * Service class for managing groups, including creating, updating, joining, leaving, and managing
 * group members.
 */
@Service
@RequiredArgsConstructor
public class GroupService {

  private final GroupRepository groupRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final UserRepository userRepository;

  /**
   * Retrieves all public groups.
   *
   * @return a list of all groups marked as public.
   */
  public List<Group> getPublicGroups() {
    List<Group> allGroups = groupRepository.findAll();
    return allGroups.stream().filter(Group::getIsPublic).toList();
  }

  /**
   * Retrieves a group by its ID.
   *
   * @param id the ID of the group.
   * @return an Optional containing the group if found.
   */
  public Optional<Group> getGroupById(Long id) {
    return groupRepository.findById(id);
  }

  /**
   * Creates a new group with the given DTO and sets the creator as admin.
   *
   * @param dto the group data transfer object containing name, description, and visibility.
   * @param creator the user who creates the group.
   * @return the newly created group.
   */
  public Group createGroup(GroupDto dto, User creator) {
    Group group = new Group(dto);
    group.setCreatedBy(creator);

    group = groupRepository.save(group);
    groupMemberRepository.save(new GroupMember(group, creator, Role.ADMIN));
    return group;
  }

  /**
   * Updates an existing group's details.
   *
   * @param id the ID of the group to update.
   * @param updatedGroup the DTO containing updated group information.
   * @return the updated group.
   * @throws GroupNotFoundException if no group exists with the given ID.
   */
  public Group updateGroup(Long id, GroupDto updatedGroup) {
    return groupRepository
        .findById(id)
        .map(
            existing -> {
              existing.setName(updatedGroup.getName());
              existing.setDescription(updatedGroup.getDescription());
              existing.setIsPublic(updatedGroup.getIsPublic());
              return groupRepository.save(existing);
            })
        .orElseThrow(() -> new GroupNotFoundException("Group not found with id: " + id));
  }

  /**
   * Deletes a group by its ID.
   *
   * @param id the ID of the group to delete.
   */
  public void deleteGroup(Long id) {
    groupRepository.deleteById(id);
  }

  /**
   * Adds a user to a group as a regular member.
   *
   * @param groupId the ID of the group to join.
   * @param currentUser the user who wants to join the group.
   * @return the group the user joined.
   * @throws GroupNotFoundException if no group exists with the given ID.
   * @throws AlreadyMemberException if the user is already a member of the group.
   * @throws PrivateGroupJoinException if the group is private.
   */
  public Group joinGroup(Long groupId, User currentUser) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new GroupNotFoundException("Group not found with id: " + groupId));

    boolean alreadyMember =
        groupMemberRepository.findByGroupAndUser(group, currentUser).isPresent();
    if (alreadyMember) {
      throw new AlreadyMemberException(groupId, currentUser.getId());
    }

    if (!Boolean.TRUE.equals(group.getIsPublic())) {
      throw new PrivateGroupJoinException(groupId);
    }

    groupMemberRepository.save(new GroupMember(group, currentUser, Role.USER));
    return group;
  }

  /**
   * Removes a user from a group.
   *
   * @param groupId the ID of the group to leave.
   * @param currentUser the user who wants to leave the group.
   * @return the group the user left.
   * @throws GroupNotFoundException if no group exists with the given ID.
   * @throws NotMemberException if the user is not a member of the group.
   */
  public Group leaveGroup(Long groupId, User currentUser) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new GroupNotFoundException("Group not found with id: " + groupId));

    GroupMember member =
        groupMemberRepository
            .findByGroupAndUser(group, currentUser)
            .orElseThrow(() -> new NotMemberException(groupId, currentUser.getId()));

    groupMemberRepository.delete(member);
    return group;
  }

  /**
   * Retrieves all members of a group.
   *
   * @param groupId the ID of the group.
   * @return a list of users who are members of the group.
   * @throws GroupNotFoundException if no group exists with the given ID.
   */
  public List<User> getMembers(Long groupId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new GroupNotFoundException("Group not found"));

    List<GroupMember> groupMembers = groupMemberRepository.findAllByGroup(group);
    return groupMembers.stream().map(GroupMember::getUser).toList();
  }

  /**
   * Removes a specific user from a group.
   *
   * <p>If the user to remove is an admin, ensures that the group still has at least one admin
   * remaining.
   *
   * @param groupId the ID of the group.
   * @param userId the ID of the user to remove.
   * @return the group after removing the member.
   * @throws GroupNotFoundException if no group exists with the given ID.
   * @throws UserNotFoundException if no user exists with the given ID.
   * @throws NotMemberException if the user is not a member of the group.
   * @throws LastAdminException if the user is the last admin in the group.
   */
  public Group removeMember(Long groupId, Long userId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new GroupNotFoundException("Group not found with id: " + groupId));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

    GroupMember memberToRemove =
        groupMemberRepository
            .findByGroupAndUser(group, user)
            .orElseThrow(() -> new NotMemberException(groupId, userId));

    if (memberToRemove.getRole() == Role.ADMIN) {
      long adminCount = groupMemberRepository.countByGroupAndRole(group, Role.ADMIN);
      if (adminCount <= 1) {
        throw new LastAdminException(groupId);
      }
    }

    groupMemberRepository.delete(memberToRemove);
    return group;
  }

  /**
   * Retrieves all groups that a user is a member of.
   *
   * @param user the user whose groups to retrieve
   * @return a list of groups where the user is a member
   */
  public List<Group> getUserGroups(User user) {
    return groupMemberRepository.findAllByUser(user).stream().map(GroupMember::getGroup).toList();
  }

  /**
   * Adds a user to a group as a regular member.
   *
   * @param groupId the ID of the group
   * @param userId the ID of the user to add
   * @return the updated group
   */
  public Group addMember(Long groupId, Long userId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new GroupNotFoundException("Group not found with id: " + groupId));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

    boolean alreadyMember = groupMemberRepository.findByGroupAndUser(group, user).isPresent();
    if (alreadyMember) {
      throw new AlreadyMemberException(groupId, userId);
    }

    groupMemberRepository.save(new GroupMember(group, user, Role.USER));
    return group;
  }
}
