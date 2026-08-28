package vaultWeb.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vaultWeb.models.enums.MessageType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
  private String content;
  private String timestamp;
  private Long groupId;
  private Long privateChatId;
  private Long senderId;
  private String senderUsername;
  @NotBlank private String senderDeviceId;

  private String e2eePayload;
  private MessageType messageType;
  private PollResponseDto poll;

  private String clientMessageId;
}
