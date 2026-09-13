package vaultWeb.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vaultWeb.models.Poll;
import vaultWeb.models.PollOption;
import vaultWeb.models.PollVote;
import vaultWeb.models.User;

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PollVoteRepositoryTest {

  @Autowired private PollVoteRepository pollVoteRepository;
  @Autowired private PollRepository pollRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private User createUser() {
    User user = new User();
    user.setUsername("voter");
    return userRepository.saveAndFlush(user);
  }

  private Poll createPoll(User author) {
    Poll poll = Poll.builder().author(author).question("Choose one").build();
    poll.setOptions(
        List.of(
            PollOption.builder().poll(poll).text("First").build(),
            PollOption.builder().poll(poll).text("Second").build()));
    return pollRepository.saveAndFlush(poll);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldRejectConcurrentVotesEvenWhenBothExistenceChecksPass(boolean differentOptions)
      throws Exception {
    User user = createUser();
    Poll poll = createPoll(user);
    CyclicBarrier barrier = new CyclicBarrier(2);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> tryVote(poll, poll.getOptions().get(0), user, barrier));
      var second =
          executor.submit(
              () -> tryVote(poll, poll.getOptions().get(differentOptions ? 1 : 0), user, barrier));

      int successes = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
      assertEquals(1, successes);
    }

    long votes =
        pollVoteRepository.findAll().stream()
            .filter(vote -> vote.getPoll().getId().equals(poll.getId()))
            .count();
    assertEquals(1, votes);
  }

  private int tryVote(Poll poll, PollOption option, User user, CyclicBarrier barrier) {
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                assertFalse(pollVoteRepository.existsByOption_PollAndUser(poll, user));
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                  throw new IllegalStateException("Concurrent votes did not synchronize", ex);
                }
                pollVoteRepository.saveAndFlush(
                    PollVote.builder().poll(poll).option(option).user(user).build());
              });
      return 1;
    } catch (DataIntegrityViolationException ex) {
      return 0;
    }
  }

  @Test
  void shouldAllowDifferentUsersAndDifferentPolls() {
    User first = createUser();
    User second = createUser();
    Poll poll = createPoll(first);
    Poll otherPoll = createPoll(first);

    pollVoteRepository.saveAndFlush(
        PollVote.builder().poll(poll).option(poll.getOptions().getFirst()).user(first).build());
    pollVoteRepository.saveAndFlush(
        PollVote.builder().poll(poll).option(poll.getOptions().getFirst()).user(second).build());
    pollVoteRepository.saveAndFlush(
        PollVote.builder()
            .poll(otherPoll)
            .option(otherPoll.getOptions().getFirst())
            .user(first)
            .build());

    long votes =
        pollVoteRepository.findAll().stream()
            .filter(
                vote ->
                    vote.getPoll().getId().equals(poll.getId())
                        || vote.getPoll().getId().equals(otherPoll.getId()))
            .count();
    assertEquals(3, votes);
  }
}
