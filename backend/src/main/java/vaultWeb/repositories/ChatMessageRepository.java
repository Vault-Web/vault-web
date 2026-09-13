package vaultWeb.repositories;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Poll;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
  List<ChatMessage> findByPrivateChatIdAndDeletedFalseOrderByTimestampAsc(Long privateChatId);

  List<ChatMessage> findByGroupIdAndDeletedFalseOrderByTimestampAsc(Long groupId);

  long countBySender(User sender);

  List<ChatMessage> findTop10BySenderOrderByTimestampDesc(User sender);

  /**
   * Returns the latest timestamp per nonempty chat without loading message payloads. Deleted
   * messages remain included, matching the dashboard's existing summary behavior.
   */
  @Query(
      """
      select m.privateChat.id as privateChatId, max(m.timestamp) as lastMessageAt
      from ChatMessage m
      where m.privateChat.id in :privateChatIds
      group by m.privateChat.id
      """)
  List<PrivateChatLastMessage> findLastMessagesByPrivateChatIds(
      @Param("privateChatIds") Collection<Long> privateChatIds);

  interface PrivateChatLastMessage {
    Long getPrivateChatId();

    Instant getLastMessageAt();
  }

  int deleteByPrivateChat(PrivateChat privateChat);

  void deleteByPoll(Poll poll);

  Optional<ChatMessage> findByClientMessageId(String clientMessageId);
}
