package vaultWeb.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vaultWeb.dtos.dashboard.UserDashboardDto;
import vaultWeb.dtos.dashboard.UserDashboardDto.GroupSummary;
import vaultWeb.dtos.dashboard.UserDashboardDto.MessagePreview;
import vaultWeb.dtos.dashboard.UserDashboardDto.PollSummary;
import vaultWeb.dtos.dashboard.UserDashboardDto.PrivateChatSummary;
import vaultWeb.dtos.dashboard.UserDashboardDto.ProfileSummary;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.Poll;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.ChatMessageRepository.PrivateChatLastMessage;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.GroupMemberRepository.GroupMemberCount;
import vaultWeb.repositories.PollRepository;
import vaultWeb.repositories.PrivateChatRepository;

/**
 * Aggregates all pieces of information a dashboard needs about a user so the frontend can render it
 * with a single API call.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

  private final GroupMemberRepository groupMemberRepository;
  private final PrivateChatRepository privateChatRepository;
  private final PollRepository pollRepository;
  private final ChatMessageRepository chatMessageRepository;

  /**
   * Builds the full dashboard payload for a given user.
   *
   * @param user the authenticated user
   * @return aggregated dashboard data
   */
  public UserDashboardDto buildDashboard(User user) {
    if (user == null) {
      throw new UnauthorizedException("No authenticated user found");
    }

    Map<Long, GroupMember> membershipByGroup =
        groupMemberRepository.findAllByUser(user).stream()
            .collect(
                Collectors.toMap(
                    membership -> membership.getGroup().getId(),
                    membership -> membership,
                    (existing, duplicate) -> existing,
                    LinkedHashMap::new));

    List<GroupMember> memberships = new ArrayList<>(membershipByGroup.values());
    List<PrivateChat> privateChats = privateChatRepository.findByUser1OrUser2(user, user);

    List<Long> groupIds =
        memberships.stream().map(membership -> membership.getGroup().getId()).distinct().toList();

    Map<Long, Long> memberCountsByGroup =
        groupIds.isEmpty()
            ? Map.of()
            : groupMemberRepository.countMembersByGroupIds(groupIds).stream()
                .collect(
                    Collectors.toMap(
                        GroupMemberCount::getGroupId, GroupMemberCount::getMemberCount));

    List<Poll> polls = groupIds.isEmpty() ? List.of() : pollRepository.findByGroupIdIn(groupIds);
    Map<Long, List<Poll>> pollsByGroup =
        polls.stream().collect(Collectors.groupingBy(poll -> poll.getGroup().getId()));

    long messageCount = chatMessageRepository.countBySender(user);
    List<MessagePreview> recentMessages = buildRecentMessages(user);

    List<GroupSummary> groupSummaries =
        buildGroupSummaries(memberships, pollsByGroup, memberCountsByGroup);
    List<PrivateChatSummary> privateChatSummaries = buildPrivateChatSummaries(privateChats, user);
    List<PollSummary> pollSummaries = buildPollSummaries(polls);

    ProfileSummary profileSummary =
        new ProfileSummary(
            user.getId(),
            user.getUsername(),
            groupSummaries.size(),
            privateChatSummaries.size(),
            messageCount,
            // Include the profile picture path (null is fine — frontend handles the fallback)
            user.getProfilePicture());

    return new UserDashboardDto(
        profileSummary, groupSummaries, privateChatSummaries, pollSummaries, recentMessages);
  }

  private List<GroupSummary> buildGroupSummaries(
      List<GroupMember> memberships,
      Map<Long, List<Poll>> pollsByGroup,
      Map<Long, Long> memberCountsByGroup) {
    return memberships.stream()
        .map(
            membership -> {
              Group group = membership.getGroup();
              long memberCount = memberCountsByGroup.getOrDefault(group.getId(), 0L);
              int pollCount = pollsByGroup.getOrDefault(group.getId(), List.of()).size();
              return new GroupSummary(
                  group.getId(),
                  group.getName(),
                  group.getDescription(),
                  membership.getRole().name(),
                  Boolean.TRUE.equals(group.getIsPublic()),
                  Math.toIntExact(memberCount),
                  group.getCreatedAt(),
                  pollCount);
            })
        .toList();
  }

  private List<PrivateChatSummary> buildPrivateChatSummaries(
      List<PrivateChat> privateChats, User currentUser) {
    List<Long> privateChatIds = privateChats.stream().map(PrivateChat::getId).distinct().toList();
    Map<Long, PrivateChatLastMessage> lastMessagesByChat =
        privateChatIds.isEmpty()
            ? Map.of()
            : chatMessageRepository.findLastMessagesByPrivateChatIds(privateChatIds).stream()
                .collect(
                    Collectors.toMap(PrivateChatLastMessage::getPrivateChatId, message -> message));

    return privateChats.stream()
        .map(
            chat -> {
              String participant = resolveParticipantName(chat, currentUser);
              PrivateChatLastMessage lastMessage = lastMessagesByChat.get(chat.getId());
              Instant lastTimestamp = lastMessage != null ? lastMessage.getLastMessageAt() : null;
              String preview = lastMessage != null ? "Encrypted message" : null;
              return new PrivateChatSummary(chat.getId(), participant, preview, lastTimestamp);
            })
        .sorted(
            Comparator.comparing(
                PrivateChatSummary::lastMessageAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  private List<PollSummary> buildPollSummaries(List<Poll> polls) {
    return polls.stream()
        .map(
            poll -> {
              int optionCount = poll.getOptions() == null ? 0 : poll.getOptions().size();
              int totalVotes =
                  poll.getOptions() == null
                      ? 0
                      : poll.getOptions().stream()
                          .mapToInt(
                              option -> option.getVotes() == null ? 0 : option.getVotes().size())
                          .sum();
              return new PollSummary(
                  poll.getId(),
                  poll.getQuestion(),
                  poll.getGroup().getId(),
                  poll.getGroup().getName(),
                  poll.isAnonymous(),
                  poll.getDeadline(),
                  optionCount,
                  totalVotes);
            })
        .sorted(
            Comparator.comparing(
                PollSummary::deadline, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private List<MessagePreview> buildRecentMessages(User user) {
    return chatMessageRepository.findTop10BySenderOrderByTimestampDesc(user).stream()
        .map(
            message ->
                new MessagePreview(
                    message.getId(),
                    buildMessagePreview(message),
                    message.getTimestamp(),
                    message.getGroup() != null ? message.getGroup().getId() : null,
                    message.getPrivateChat() != null ? message.getPrivateChat().getId() : null))
        .toList();
  }

  private String resolveParticipantName(PrivateChat chat, User currentUser) {
    if (chat.getUser1() == null && chat.getUser2() == null) {
      return "Unknown";
    }

    if (chat.getUser1() != null && chat.getUser1().getId().equals(currentUser.getId())) {
      return chat.getUser2() != null ? chat.getUser2().getUsername() : currentUser.getUsername();
    }

    return chat.getUser1() != null ? chat.getUser1().getUsername() : currentUser.getUsername();
  }

  private String buildMessagePreview(ChatMessage message) {
    if (message == null) {
      return null;
    }
    return "Encrypted message";
  }
}
