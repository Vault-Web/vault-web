package vaultWeb.services;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.models.enums.MessageStatus;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;

/**
 * Service class for handling private chats between two users.
 *
 * <p>Provides functionality to retrieve an existing private chat or create a new one if it does not
 * exist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrivateChatService {

  private final PrivateChatRepository privateChatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMemberRepository groupMemberRepository;

  /**
   * Retrieves an existing private chat between two users or creates a new one if none exists.
   *
   * @param username1 The username of the first user.
   * @param username2 The username of the second user.
   * @return The existing or newly created PrivateChat entity.
   * @throws UserNotFoundException if either user does not exist.
   */
  public PrivateChat getOrCreatePrivateChat(String username1, String username2) {
    // Find the first user by username, throw exception if not found
    User user1 =
        userRepository
            .findByUsername(username1)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + username1));

    // Find the second user by username, throw exception if not found
    User user2 =
        userRepository
            .findByUsername(username2)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + username2));

    // Check if a private chat already exists in one direction (user1 -> user2)
    Optional<PrivateChat> chatOpt = privateChatRepository.findByUser1AndUser2(user1, user2);
    if (chatOpt.isPresent()) return chatOpt.get();

    // Check if a private chat exists in the opposite direction (user2 -> user1)
    chatOpt = privateChatRepository.findByUser2AndUser1(user1, user2);
    if (chatOpt.isPresent()) return chatOpt.get();

    // If no chat exists, create a new PrivateChat
    PrivateChat privateChat = new PrivateChat();
    privateChat.setUser1(user1);
    privateChat.setUser2(user2);

    // Save and return the new private chat
    return privateChatRepository.save(privateChat);
  }

  @Transactional
  public int clearMultipleChats(List<Long> privateChatIds, String currentUsername) {
    User currentUser =
        userRepository
            .findByUsername(currentUsername)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + currentUsername));
    int totalCount = 0;
    for (Long privateChatId : privateChatIds) {
      PrivateChat chat =
          privateChatRepository
              .findById(privateChatId)
              .orElseThrow(
                  () ->
                      new PrivateChatNotFoundException(
                          "No private chat with this id " + privateChatId));

      // verify if user is part of this chat
      if (!(chat.getUser1().getId().equals(currentUser.getId())
          || chat.getUser2().getId().equals(currentUser.getId()))) {
        throw new AccessDeniedException(
            "You are not allowed to delete a private chat with this id " + privateChatId);
      }

      int count = chatMessageRepository.deleteByPrivateChat(chat);
      totalCount += count;
      log.info("Cleared {} messages from private chat {}", count, privateChatId);
    }
    return totalCount;
  }

  @Transactional
  public int markChatAsRead(Long privateChatId, String currentUsername) {
    User currentUser =
        userRepository
            .findByUsername(currentUsername)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + currentUsername));
    PrivateChat chat =
        privateChatRepository
            .findById(privateChatId)
            .orElseThrow(
                () ->
                    new PrivateChatNotFoundException(
                        "No private chat with this id " + privateChatId));

    if (!(chat.getUser1().getId().equals(currentUser.getId())
        || chat.getUser2().getId().equals(currentUser.getId()))) {
      throw new AccessDeniedException("You are not a participant of chat " + privateChatId);
    }

    return chatMessageRepository.markPrivateChatAsRead(
        privateChatId, currentUser.getId(), MessageStatus.READ, Instant.now());
  }

  public List<PrivateChat> getUserPrivateChats(String username) {
    User user =
        userRepository
            .findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
    return privateChatRepository.findByUser1OrUser2(user, user);
  }

  @Transactional
  public Long createGroupFromChats(
      List<Long> privateChatIds, String groupName, String description, String currentUserName) {
    User creator =
        userRepository
            .findByUsername(currentUserName)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + currentUserName));

    Group group =
        Group.builder()
            .name(groupName)
            .description(description)
            .createdBy(creator)
            .createdAt(Instant.now())
            .isPublic(false)
            .build();

    Set<User> participants = new HashSet<>();
    participants.add(creator);

    for (Long privateChatId : privateChatIds) {
      PrivateChat privateChat =
          privateChatRepository
              .findById(privateChatId)
              .orElseThrow(
                  () ->
                      new PrivateChatNotFoundException(
                          "No private chat with this id " + privateChatId));
      boolean isOwnerIsUser1 = privateChat.getUser1().getId().equals(creator.getId());
      boolean isOwnerIsUser2 = privateChat.getUser2().getId().equals(creator.getId());
      if (!(isOwnerIsUser1 || isOwnerIsUser2)) {
        throw new AccessDeniedException(
            "User is not allowed to create group with chat where he didn't participate in "
                + privateChatId);
      }
      participants.add(privateChat.getUser1());
      participants.add(privateChat.getUser2());
    }
    group = groupRepository.save(group);
    for (User participant : participants) {
      GroupMember groupMember =
          GroupMember.builder()
              .group(group)
              .user(participant)
              .role((participant.equals(creator)) ? Role.ADMIN : Role.USER)
              .build();
      groupMemberRepository.save(groupMember);
    }
    return group.getId();
  }
}
