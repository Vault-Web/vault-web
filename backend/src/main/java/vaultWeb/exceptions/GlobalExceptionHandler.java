package vaultWeb.exceptions;

import java.nio.file.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.exceptions.notfound.UserNotFoundException;

/**
 * Global exception handler for all controllers in the "vaultWeb.controllers" package.
 *
 * <p>Catches specific exceptions and returns appropriate HTTP status codes and messages.
 */
@ControllerAdvice(basePackages = "vaultWeb.controllers")
public class GlobalExceptionHandler {

  /** Handles UserNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<String> handleUserNotFound(UserNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found: " + ex.getMessage());
  }

  /** Handles GroupNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(GroupNotFoundException.class)
  public ResponseEntity<String> handleGroupNotFound(GroupNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Group not found: " + ex.getMessage());
  }

  /** Handles UnauthorizedException and returns 401 Unauthorized. */
  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<String> handleUnauthorized(UnauthorizedException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized: " + ex.getMessage());
  }

  /** Handles AccessDeniedException and returns 403 Forbidden. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<String> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: " + ex.getMessage());
  }

  /** Handles AdminAccessDeniedException and returns 403 Forbidden. */
  @ExceptionHandler(AdminAccessDeniedException.class)
  public ResponseEntity<String> handleAdminAccessDenied(AdminAccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body("Admin access denied: " + ex.getMessage());
  }

  /** Handles AlreadyMemberException and returns 409 Conflict. */
  @ExceptionHandler(AlreadyMemberException.class)
  public ResponseEntity<String> handleAlreadyMember(AlreadyMemberException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body("Membership error: " + ex.getMessage());
  }

  /** Handles NotMemberException and returns 403 Forbidden. */
  @ExceptionHandler(NotMemberException.class)
  public ResponseEntity<String> handleNotMember(NotMemberException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Membership error: " + ex.getMessage());
  }

  /** Handles DuplicateUsernameException and returns 409 Conflict. */
  @ExceptionHandler(DuplicateUsernameException.class)
  public ResponseEntity<String> handleDuplicateUsername(DuplicateUsernameException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body("Registration error: " + ex.getMessage());
  }

  /** Handles BadCredentialsException (invalid login) and returns 403 Forbidden. */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<String> handleBadCredentials(BadCredentialsException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication failed");
  }

  /** Handles AlreadyVotedException and returns 400 Bad Request. */
  @ExceptionHandler(AlreadyVotedException.class)
  public ResponseEntity<String> handleAlreadyVoted(AlreadyVotedException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Poll error: " + ex.getMessage());
  }

  /** Handles DecryptionFailedException and returns 500 Internal Server Error. */
  @ExceptionHandler(DecryptionFailedException.class)
  public ResponseEntity<String> handleDecryptionFailed(DecryptionFailedException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body("Chat error: " + ex.getMessage());
  }

  /** Handles EncryptionFailedException and returns 500 Internal Server Error. */
  @ExceptionHandler(EncryptionFailedException.class)
  public ResponseEntity<String> handleEncryptionFailed(EncryptionFailedException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body("Chat error: " + ex.getMessage());
  }

  /** Handles PollDoesNotBelongToGroupException and returns 404 Not Found. */
  @ExceptionHandler(PollDoesNotBelongToGroupException.class)
  public ResponseEntity<String> handlePollDoesNotBelongToGroup(
      PollDoesNotBelongToGroupException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Poll error: " + ex.getMessage());
  }

  /** Handles PollOptionNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(PollOptionNotFoundException.class)
  public ResponseEntity<String> handlePollOptionNotFound(PollOptionNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Poll error: " + ex.getMessage());
  }

  /** Handles IllegalArgumentException (validation failures) and returns 400 Bad Request. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Bad request: " + ex.getMessage());
  }

  /** Handles MaxUploadSizeExceededException (multipart too large) and returns 400 Bad Request. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<String> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
    // Provide a concise, user-friendly message without exposing internal details
    String sizeLabel = resolveConfiguredMaxUploadSizeLabel();
    String message =
        (sizeLabel != null)
            ? "File size exceeds the maximum allowed limit of " + sizeLabel
            : "File size exceeds the maximum allowed limit";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
  }

  /** Handles any other RuntimeException and returns 500 Internal Server Error. */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<String> handleRuntimeException(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body("Internal error: " + ex.getMessage());
  }

  // --- Helpers ---

  @Value("${spring.servlet.multipart.max-file-size:}")
  private String maxUploadSizeProp;

  private String resolveConfiguredMaxUploadSizeLabel() {
    try {
      if (maxUploadSizeProp == null || maxUploadSizeProp.isBlank()) {
        return null;
      }
      DataSize size = DataSize.parse(maxUploadSizeProp.trim());
      long bytes = size.toBytes();
      // Prefer MB if evenly divisible, then KB, otherwise bytes
      long mb = bytes / (1024 * 1024);
      if (mb > 0 && (bytes % (1024 * 1024) == 0)) {
        return mb + "MB";
      }
      long kb = bytes / 1024;
      if (kb > 0 && (bytes % 1024 == 0)) {
        return kb + "KB";
      }
      return bytes + "B";
    } catch (Exception e) {
      return null; // Fallback to generic message on parse issues
    }
  }
}
