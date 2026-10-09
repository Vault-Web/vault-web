package vaultWeb.exceptions;

import lombok.Getter;

/** Wraps a failed private message send so the error reply can carry its clientMessageId. */
@Getter
public class PrivateMessageSendException extends RuntimeException {
  private final String clientMessageId;

  public PrivateMessageSendException(String clientMessageId, RuntimeException cause) {
    super(cause.getMessage(), cause);
    this.clientMessageId = clientMessageId;
  }
}
