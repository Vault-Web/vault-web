package vaultWeb.models;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import vaultWeb.models.enums.MessageType;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = true, columnDefinition = "TEXT")
  private String e2eePayload;

  @Column(length = 64)
  private String senderDeviceId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sender_id", nullable = false)
  private User sender;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "group_id", nullable = true)
  private Group group;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "private_chat_id", nullable = true)
  private PrivateChat privateChat;

  private Instant timestamp;

  @Enumerated(EnumType.STRING)
  private MessageType messageType;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "poll_id")
  private Poll poll;

  @Column(name = "client_message_id", length = 36, nullable = true)
  private String clientMessageId;
}
