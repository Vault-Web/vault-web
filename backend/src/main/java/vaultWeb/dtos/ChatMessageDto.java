package vaultWeb.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
  private String content;
  private String cipherText;
  private String iv;
  private String timestamp;
  private Long groupId;
  private Long privateChatId;
  private Long senderId;
  private String senderUsername;
}
