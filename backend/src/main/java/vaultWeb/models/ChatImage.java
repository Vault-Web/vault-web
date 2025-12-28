package vaultWeb.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "chat_images")
public class ChatImage {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Lob
  @Column(name = "image_content", nullable = false, columnDefinition = "bytea")
  private byte[] imageContent;

  @Column(name = "sender_id")
  private Integer senderId;

  @Column(name = "receiver_id")
  private Integer receiverId;

  @NotNull
  @Column(name = "createdon", nullable = false)
  private OffsetDateTime createdon;
}
