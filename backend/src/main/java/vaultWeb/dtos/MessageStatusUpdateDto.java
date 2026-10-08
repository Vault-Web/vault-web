package vaultWeb.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vaultWeb.models.enums.MessageStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageStatusUpdateDto {
  private Long privateChatId;
  private String clientMessageId;
  private MessageStatus status;
  private String timestamp;
}
