package vaultWeb.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.dtos.PollRequestDto;
import vaultWeb.dtos.PollResponseDto;
import vaultWeb.exceptions.AlreadyVotedException;
import vaultWeb.exceptions.PollDoesNotBelongToGroupException;
import vaultWeb.exceptions.PollOptionNotFoundException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.exceptions.notfound.PollNotFoundException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.models.*;
import vaultWeb.models.enums.MessageType;
import vaultWeb.repositories.*;

/**
 * Service class responsible for managing polls within groups.
 *
 * <p>Provides functionalities to create, update, delete, retrieve, and vote on polls. It also
 * converts Poll entities to PollResponseDto objects for API responses.
 */
@Service
@RequiredArgsConstructor
public class PollService {

  private final PollRepository pollRepository;
  private final GroupRepository groupRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final PollVoteRepository pollVoteRepository;
  private final PrivateChatRepository privateChatRepository;
  private final ChatMessageRepository chatMessageRepository;

  /**
   * Creates a new poll in the specified group by the given author.
   *
   * @param pollContext the group or private chat in which the poll will be created
   * @param author the user who creates the poll
   * @param pollDto the data transfer object containing poll details
   * @return the created Poll entity
   * @throws NotMemberException if the author is not a member of the group
   */
  public Poll createPoll(PollContext pollContext, User author, PollRequestDto pollDto) {

    validateContextAccess(pollContext, author);
    Instant deadlineInstant =
        pollDto.getDeadline() != null ? pollDto.getDeadline().toInstant() : null;

    Poll poll =
        Poll.builder()
            .group(pollContext.group())
            .privateChat(pollContext.privateChat())
            .author(author)
            .question(pollDto.getQuestion())
            .deadline(deadlineInstant)
            .isAnonymous(pollDto.isAnonymous())
            .build();

    List<PollOption> options =
        pollDto.getOptions().stream()
            .map(optionText -> PollOption.builder().poll(poll).text(optionText).build())
            .collect(Collectors.toList());

    poll.setOptions(options);

    Poll savedPoll = pollRepository.save(poll);
    ChatMessage message =
        ChatMessage.builder()
            .sender(author)
            .group(pollContext.group())
            .privateChat(pollContext.privateChat())
            .timestamp(Instant.now())
            .messageType(MessageType.POLL)
            .poll(savedPoll)
            .build();

    chatMessageRepository.save(message);

    return savedPoll;
  }

  private void validateContextAccess(PollContext pollContext, User user) {
    String username = user.getUsername();
    if (pollContext.isGroup()) {
      boolean isNotGroupMember =
          groupMemberRepository.findByGroupAndUser(pollContext.group(), user).isEmpty();
      if (isNotGroupMember) {
        throw new NotMemberException(pollContext.group().getId(), user.getId());
      }
    } else if (pollContext.isPrivateChat()) {
      PrivateChat chat = pollContext.privateChat();
      boolean isChatParticipant =
          (chat.getUser1() != null && username.equals(chat.getUser1().getUsername()))
              || (chat.getUser2() != null && username.equals(chat.getUser2().getUsername()));

      if (!isChatParticipant) {
        throw new UnauthorizedException("User is not a participant in this chat");
      }
    } else {
      throw new IllegalStateException(("Poll has no Owner"));
    }
  }

  /**
   * Retrieves all polls for a given group.
   *
   * @param groupId the ID of the group
   * @param currentUser the current user requesting the polls
   * @return a list of polls in the group
   * @throws GroupNotFoundException if the group does not exist
   * @throws NotMemberException if the user is not a member of the group
   */
  public List<Poll> getPollsByGroup(Long groupId, User currentUser) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(
                () -> new GroupNotFoundException("Group with id " + groupId + " not found"));
    if (groupMemberRepository.findByGroupAndUser(group, currentUser).isEmpty()) {
      throw new NotMemberException(group.getId(), currentUser.getId());
    }

    return pollRepository.findByGroupId(groupId);
  }

  public List<Poll> getPollsByPrivateChat(Long privateChatId, User currentUser) {
    PrivateChat privateChat =
        privateChatRepository
            .findById(privateChatId)
            .orElseThrow(
                () ->
                    new PrivateChatNotFoundException(
                        "Private chat with id " + privateChatId + " not found"));

    validateContextAccess(new PollContext(null, privateChat), currentUser);
    return pollRepository.findByPrivateChatId(privateChatId);
  }

  /**
   * Converts a Poll entity to a PollResponseDto suitable for API responses.
   *
   * @param poll the Poll entity to convert
   * @return a PollResponseDto representing the poll and its options
   */
  public PollResponseDto toResponseDto(Poll poll) {
    List<PollResponseDto.OptionResultDto> options =
        poll.getOptions().stream()
            .map(
                option -> {
                  List<String> voters =
                      poll.isAnonymous()
                          ? List.of()
                          : option.getVotes() == null
                              ? List.of()
                              : option.getVotes().stream()
                                  .map(vote -> vote.getUser().getUsername())
                                  .collect(Collectors.toList());

                  int voteCount = option.getVotes() != null ? option.getVotes().size() : 0;

                  return new PollResponseDto.OptionResultDto(
                      option.getId(), option.getText(), voteCount, voters);
                })
            .collect(Collectors.toList());

    return new PollResponseDto(poll.getId(), poll.getQuestion(), poll.isAnonymous(), options);
  }

  private PollContext toPollContext(Poll poll) {

    return new PollContext(poll.getGroup(), poll.getPrivateChat());
  }

  private Poll getPollForGroup(Long groupId, Long pollId) {
    Poll poll =
        pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));
    if (poll.getGroup() == null || !poll.getGroup().getId().equals(groupId)) {
      throw new PollDoesNotBelongToGroupException(
          "Poll with id " + pollId + " does not belong to group with id " + groupId);
    }
    return poll;
  }

  private Poll getPollForPrivateChat(Long privateChatId, Long pollId) {
    Poll poll =
        pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));
    if (poll.getPrivateChat() == null || !poll.getPrivateChat().getId().equals(privateChatId)) {
      throw new PollDoesNotBelongToGroupException(
          "Poll with id " + pollId + " does not belong to private chat with id " + privateChatId);
    }
    return poll;
  }

  /**
   * Allows a user to vote for a specific option in a poll within a group.
   *
   * @param pollId the ID of the poll
   * @param optionId the ID of the option to vote for
   * @param user the user casting the vote
   * @throws GroupNotFoundException if the group does not exist
   * @throws NotMemberException if the user is not a member of the group
   * @throws PollNotFoundException if the poll does not exist
   * @throws PollDoesNotBelongToGroupException if the poll doesn't belong to the group
   * @throws PollOptionNotFoundException if the poll option invalid
   * @throws AlreadyVotedException if the user has already voted
   */
  @Transactional
  public void vote(Long pollId, Long optionId, User user) {

    Poll poll =
        pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));

    vote(poll, optionId, user);
  }

  @Transactional
  public void voteInGroup(Long groupId, Long pollId, Long optionId, User user) {
    vote(getPollForGroup(groupId, pollId), optionId, user);
  }

  @Transactional
  public void voteInPrivateChat(Long privateChatId, Long pollId, Long optionId, User user) {
    vote(getPollForPrivateChat(privateChatId, pollId), optionId, user);
  }

  private void vote(Poll poll, Long optionId, User user) {
    PollContext pollContext = toPollContext(poll);
    validateContextAccess(pollContext, user);

    PollOption option =
        poll.getOptions().stream()
            .filter(o -> o.getId().equals(optionId))
            .findFirst()
            .orElseThrow(
                () ->
                    new PollOptionNotFoundException(
                        "optionId: " + optionId + " not found in pollId: " + poll.getId()));

    if (pollVoteRepository.existsByOption_PollAndUser(poll, user)) {
      throw new AlreadyVotedException(poll.getId(), user.getId());
    }

    PollVote vote = PollVote.builder().poll(poll).option(option).user(user).build();

    try {
      pollVoteRepository.saveAndFlush(vote);
    } catch (DataIntegrityViolationException ex) {
      for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
        if (cause instanceof ConstraintViolationException violation
            && "uk_poll_votes_poll_user".equalsIgnoreCase(violation.getConstraintName())) {
          throw new AlreadyVotedException(poll.getId(), user.getId());
        }
      }
      throw ex;
    }

    if (option.getVotes() == null) {
      option.setVotes(new ArrayList<>());
    }
    option.getVotes().add(vote);
  }

  /**
   * Updates an existing poll authored by a user.
   *
   * @param pollId the ID of the poll to update
   * @param user the author of the poll
   * @param pollDto the new poll data
   * @return the updated Poll entity
   * @throws PollNotFoundException if the poll does not exist
   * @throws UnauthorizedException if the user is not the author
   * @throws PollDoesNotBelongToGroupException if the poll doesn't belong to the group
   */
  public Poll updatePoll(Long pollId, User user, PollRequestDto pollDto) {
    Poll poll =
        pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));

    return updatePoll(poll, user, pollDto);
  }

  public Poll updatePollInGroup(Long groupId, Long pollId, User user, PollRequestDto pollDto) {
    return updatePoll(getPollForGroup(groupId, pollId), user, pollDto);
  }

  public Poll updatePollInPrivateChat(
      Long privateChatId, Long pollId, User user, PollRequestDto pollDto) {
    return updatePoll(getPollForPrivateChat(privateChatId, pollId), user, pollDto);
  }

  private Poll updatePoll(Poll poll, User user, PollRequestDto pollDto) {
    PollContext pollContext = toPollContext(poll);
    validateContextAccess(pollContext, user);

    if (!poll.getAuthor().getId().equals(user.getId())) {
      throw new UnauthorizedException("Only the author can edit the poll");
    }

    Instant deadlineInstant =
        pollDto.getDeadline() != null ? pollDto.getDeadline().toInstant() : null;

    poll.setQuestion(pollDto.getQuestion());
    poll.setDeadline(deadlineInstant);
    poll.setAnonymous(pollDto.isAnonymous());

    poll.getOptions().clear();

    List<PollOption> newOptions =
        pollDto.getOptions().stream()
            .map(optionText -> PollOption.builder().poll(poll).text(optionText).build())
            .toList();

    poll.getOptions().addAll(newOptions);

    return pollRepository.save(poll);
  }

  /**
   * Deletes a poll authored by a user.
   *
   * @param pollId the ID of the poll to delete
   * @param user the author of the poll
   * @throws PollNotFoundException if the poll does not exist
   * @throws UnauthorizedException if the user is not the author
   * @throws PollDoesNotBelongToGroupException if the poll doesn't belong to the group
   */
  @Transactional
  public void deletePoll(Long pollId, User user) {
    Poll poll =
        pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));
    deletePoll(poll, user);
  }

  @Transactional
  public void deletePollInGroup(Long groupId, Long pollId, User user) {
    deletePoll(getPollForGroup(groupId, pollId), user);
  }

  @Transactional
  public void deletePollInPrivateChat(Long privateChatId, Long pollId, User user) {
    deletePoll(getPollForPrivateChat(privateChatId, pollId), user);
  }

  private void deletePoll(Poll poll, User user) {
    PollContext pollContext = toPollContext(poll);
    validateContextAccess(pollContext, user);

    if (!poll.getAuthor().getId().equals(user.getId())) {
      throw new UnauthorizedException("Only the author can delete the poll");
    }
    chatMessageRepository.deleteByPoll(poll);
    pollRepository.delete(poll);
  }
}
