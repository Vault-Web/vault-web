package vaultWeb.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository.PrivateChatLastMessage;

@DataJpaTest
@ActiveProfiles("test")
class ChatMessageRepositoryTest {

  @Autowired private TestEntityManager entityManager;
  @Autowired private ChatMessageRepository chatMessageRepository;

  @Test
  void shouldReturnLatestTimestampPerRequestedNonemptyChat() {
    User user = entityManager.persist(new User());
    PrivateChat first = entityManager.persist(PrivateChat.builder().user1(user).build());
    PrivateChat second = entityManager.persist(PrivateChat.builder().user1(user).build());
    PrivateChat empty = entityManager.persist(PrivateChat.builder().user1(user).build());
    PrivateChat excluded = entityManager.persist(PrivateChat.builder().user1(user).build());
    Instant older = Instant.parse("2026-09-01T10:00:00Z");
    Instant newer = older.plusSeconds(60);

    persistMessage(first, user, newer, false);
    persistMessage(first, user, older, false);
    persistMessage(first, user, newer, false);
    persistMessage(second, user, older, false);
    persistMessage(second, user, newer, true);
    persistMessage(excluded, user, newer.plusSeconds(60), false);
    entityManager.flush();
    entityManager.clear();

    Map<Long, PrivateChatLastMessage> messagesByChat =
        chatMessageRepository
            .findLastMessagesByPrivateChatIds(List.of(first.getId(), second.getId(), empty.getId()))
            .stream()
            .collect(
                Collectors.toMap(PrivateChatLastMessage::getPrivateChatId, Function.identity()));

    assertEquals(2, messagesByChat.size());
    assertEquals(newer, messagesByChat.get(first.getId()).getLastMessageAt());
    assertEquals(newer, messagesByChat.get(second.getId()).getLastMessageAt());
  }

  private void persistMessage(PrivateChat chat, User sender, Instant timestamp, boolean deleted) {
    entityManager.persist(
        ChatMessage.builder()
            .privateChat(chat)
            .sender(sender)
            .timestamp(timestamp)
            .deleted(deleted)
            .build());
  }
}
