package vaultWeb.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a user tries to self-join a private group. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PrivateGroupJoinException extends RuntimeException {

  /**
   * Constructs a new PrivateGroupJoinException for a specific group.
   *
   * @param groupId the ID of the private group the user tried to join
   */
  public PrivateGroupJoinException(Long groupId) {
    super("Cannot self-join private group with id: " + groupId);
  }
}
