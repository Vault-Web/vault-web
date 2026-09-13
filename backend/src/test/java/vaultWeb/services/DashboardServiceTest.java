package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vaultWeb.dtos.dashboard.UserDashboardDto;
import vaultWeb.models.Group;
import vaultWeb.models.GroupMember;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.models.enums.Role;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.ChatMessageRepository.PrivateChatLastMessage;
import vaultWeb.repositories.GroupMemberRepository;
import vaultWeb.repositories.GroupMemberRepository.GroupMemberCount;
import vaultWeb.repositories.PollRepository;
import vaultWeb.repositories.PrivateChatRepository;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private PrivateChatRepository privateChatRepository;
  @Mock private PollRepository pollRepository;
  @Mock private ChatMessageRepository chatMessageRepository;

  @InjectMocks private DashboardService dashboardService;

  @Test
  void shouldLoadLastMessagesForAllPrivateChatsWithOneQuery() {
    User user = new User();
    user.setId(1L);
    user.setUsername("alice");
    User participant = new User();
    participant.setId(2L);
    participant.setUsername("bob");
    Instant older = Instant.parse("2026-09-01T10:00:00Z");
    Instant newer = older.plusSeconds(60);
    PrivateChatLastMessage firstMessage = lastMessage(10L, older);
    PrivateChatLastMessage secondMessage = lastMessage(20L, newer);

    when(privateChatRepository.findByUser1OrUser2(user, user))
        .thenReturn(
            List.of(
                new PrivateChat(10L, user, participant),
                new PrivateChat(20L, participant, user),
                new PrivateChat(30L, user, participant)));
    when(chatMessageRepository.findLastMessagesByPrivateChatIds(List.of(10L, 20L, 30L)))
        .thenReturn(List.of(secondMessage, firstMessage));

    UserDashboardDto dashboard = dashboardService.buildDashboard(user);

    assertEquals(
        List.of(
            new UserDashboardDto.PrivateChatSummary(20L, "bob", "Encrypted message", newer),
            new UserDashboardDto.PrivateChatSummary(10L, "bob", "Encrypted message", older),
            new UserDashboardDto.PrivateChatSummary(30L, "bob", null, null)),
        dashboard.privateChats());
    assertEquals(3, dashboard.profile().privateChatCount());
    verify(chatMessageRepository).findLastMessagesByPrivateChatIds(List.of(10L, 20L, 30L));
    verify(chatMessageRepository).countBySender(user);
    verify(chatMessageRepository).findTop10BySenderOrderByTimestampDesc(user);
    verifyNoMoreInteractions(chatMessageRepository);
  }

  @Test
  void shouldSkipLastMessageQueryWhenThereAreNoPrivateChats() {
    User user = new User();
    user.setId(1L);

    UserDashboardDto dashboard = dashboardService.buildDashboard(user);

    assertEquals(List.of(), dashboard.privateChats());
    verify(chatMessageRepository).countBySender(user);
    verify(chatMessageRepository).findTop10BySenderOrderByTimestampDesc(user);
    verifyNoMoreInteractions(chatMessageRepository);
  }

  private PrivateChatLastMessage lastMessage(Long privateChatId, Instant timestamp) {
    PrivateChatLastMessage projection = mock(PrivateChatLastMessage.class);
    when(projection.getPrivateChatId()).thenReturn(privateChatId);
    when(projection.getLastMessageAt()).thenReturn(timestamp);
    return projection;
  }

  @Test
  void shouldLoadMemberCountsForAllGroupsWithOneQuery() {
    User user = new User();
    user.setId(1L);
    user.setUsername("alice");

    Group firstGroup = createGroup(10L, "First");
    Group secondGroup = createGroup(20L, "Second");
    List<GroupMember> memberships =
        List.of(
            new GroupMember(firstGroup, user, Role.ADMIN),
            new GroupMember(secondGroup, user, Role.USER));

    GroupMemberCount firstCount = memberCount(10L, 4L);
    GroupMemberCount secondCount = memberCount(20L, 7L);

    when(groupMemberRepository.findAllByUser(user)).thenReturn(memberships);
    when(groupMemberRepository.countMembersByGroupIds(
            argThat(groupIds -> Set.copyOf(groupIds).equals(Set.of(10L, 20L)))))
        .thenReturn(List.of(firstCount, secondCount));
    when(privateChatRepository.findByUser1OrUser2(user, user)).thenReturn(List.of());
    when(pollRepository.findByGroupIdIn(List.of(10L, 20L))).thenReturn(List.of());
    when(chatMessageRepository.findTop10BySenderOrderByTimestampDesc(user)).thenReturn(List.of());

    UserDashboardDto dashboard = dashboardService.buildDashboard(user);

    Map<Long, UserDashboardDto.GroupSummary> groupsById =
        dashboard.groups().stream()
            .collect(Collectors.toMap(UserDashboardDto.GroupSummary::id, Function.identity()));
    assertEquals(4, groupsById.get(10L).memberCount());
    assertEquals(7, groupsById.get(20L).memberCount());
    verify(groupMemberRepository)
        .countMembersByGroupIds(argThat(groupIds -> Set.copyOf(groupIds).equals(Set.of(10L, 20L))));
  }

  @Test
  void shouldUseZeroWhenAGroupHasNoReturnedMemberCount() {
    User user = new User();
    user.setId(1L);
    user.setUsername("alice");
    Group group = createGroup(10L, "First");

    when(groupMemberRepository.findAllByUser(user))
        .thenReturn(List.of(new GroupMember(group, user, Role.USER)));
    when(groupMemberRepository.countMembersByGroupIds(List.of(10L))).thenReturn(List.of());
    when(privateChatRepository.findByUser1OrUser2(user, user)).thenReturn(List.of());
    when(pollRepository.findByGroupIdIn(List.of(10L))).thenReturn(List.of());
    when(chatMessageRepository.findTop10BySenderOrderByTimestampDesc(user)).thenReturn(List.of());

    UserDashboardDto dashboard = dashboardService.buildDashboard(user);

    assertEquals(0, dashboard.groups().getFirst().memberCount());
  }

  private Group createGroup(Long id, String name) {
    Group group = new Group();
    group.setId(id);
    group.setName(name);
    group.setIsPublic(false);
    return group;
  }

  private GroupMemberCount memberCount(Long groupId, long count) {
    GroupMemberCount projection = mock(GroupMemberCount.class);
    when(projection.getGroupId()).thenReturn(groupId);
    when(projection.getMemberCount()).thenReturn(count);
    return projection;
  }
}
