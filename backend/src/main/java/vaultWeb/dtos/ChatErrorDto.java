package vaultWeb.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatErrorDto {
  private String error;

  /** Set when a private message send fails, so the client can mark that message as FAILED. */
  private String clientMessageId;

  public ChatErrorDto(String error) {
    this.error = error;
  }
}
