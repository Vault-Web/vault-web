package vaultWeb.exceptions;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.NotMemberException;
import vaultWeb.exceptions.notfound.PrivateChatNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;

/**
 * Global exception handler for all controllers in the "vaultWeb.controllers" package.
 *
 * <p>Catches specific exceptions and returns appropriate HTTP status codes and messages.
 */
@ControllerAdvice(basePackages = "vaultWeb.controllers")
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, Instant.now()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidationErrors(
      MethodArgumentNotValidException ex) {
    log.warn("Validation failed for request body", ex);
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
  }

  /** Handles UserNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex) {
    log.warn("User not found", ex);
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  /** Handles GroupNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(GroupNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleGroupNotFound(GroupNotFoundException ex) {
    log.warn("Group not found", ex);
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  /** Handles PrivateChatNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(PrivateChatNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handlePrivateChatNotFound(
      PrivateChatNotFoundException ex) {
    log.warn("Private chat not found", ex);
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  /** Handles UnauthorizedException and returns 401 Unauthorized. */
  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnauthorized(UnauthorizedException ex) {
    log.warn("Unauthorized request", ex);
    return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized");
  }

  /** Handles AccessDeniedException and returns 403 Forbidden. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    log.warn("Access denied", ex);
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");
  }

  /** Handles AdminAccessDeniedException and returns 403 Forbidden. */
  @ExceptionHandler(AdminAccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAdminAccessDenied(AdminAccessDeniedException ex) {
    log.warn("Admin access denied", ex);
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");
  }

  /** Handles AlreadyMemberException and returns 409 Conflict. */
  @ExceptionHandler(AlreadyMemberException.class)
  public ResponseEntity<ApiErrorResponse> handleAlreadyMember(AlreadyMemberException ex) {
    log.warn("Membership conflict", ex);
    return error(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT", "Membership conflict");
  }

  @ExceptionHandler(ProjectConflictException.class)
  public ResponseEntity<ApiErrorResponse> handleProjectConflict(ProjectConflictException ex) {
    return error(HttpStatus.CONFLICT, "PROJECT_CONFLICT", ex.getMessage());
  }

  /** Handles NotMemberException and returns 403 Forbidden. */
  @ExceptionHandler(NotMemberException.class)
  public ResponseEntity<ApiErrorResponse> handleNotMember(NotMemberException ex) {
    log.warn("Not a member", ex);
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");
  }

  /** Handles DuplicateUsernameException and returns 409 Conflict. */
  @ExceptionHandler(DuplicateUsernameException.class)
  public ResponseEntity<ApiErrorResponse> handleDuplicateUsername(DuplicateUsernameException ex) {
    log.warn("Duplicate username during registration", ex);
    return error(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Registration failed");
  }

  /** Handles BadCredentialsException (invalid login) and returns 403 Forbidden. */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
    return error(HttpStatus.UNAUTHORIZED, "AUTH_FAILED", "Authentication failed");
  }

  /** Handles AlreadyVotedException and returns 400 Bad Request. */
  @ExceptionHandler(AlreadyVotedException.class)
  public ResponseEntity<ApiErrorResponse> handleAlreadyVoted(AlreadyVotedException ex) {
    log.warn("Invalid poll vote request", ex);
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid poll request");
  }

  /** Handles illegal client arguments and returns 400 Bad Request. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    log.warn("Invalid request payload", ex);
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request");
  }

  /**
   * Handles InvalidFileException (bad upload type or size) and returns 400 Bad Request. The message
   * from the exception is safe to surface to the client (we wrote it ourselves).
   */
  @ExceptionHandler(InvalidFileException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidFile(InvalidFileException ex) {
    log.warn("Invalid file upload attempt", ex);
    return error(HttpStatus.BAD_REQUEST, "INVALID_FILE", ex.getMessage());
  }

  /** Handles DecryptionFailedException and returns 500 Internal Server Error. */
  @ExceptionHandler(DecryptionFailedException.class)
  public ResponseEntity<ApiErrorResponse> handleDecryptionFailed(DecryptionFailedException ex) {
    log.error("Chat decryption failed", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "CHAT_ERROR", "Chat error");
  }

  /** Handles EncryptionFailedException and returns 500 Internal Server Error. */
  @ExceptionHandler(EncryptionFailedException.class)
  public ResponseEntity<ApiErrorResponse> handleEncryptionFailed(EncryptionFailedException ex) {
    log.error("Chat encryption failed", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "CHAT_ERROR", "Chat error");
  }

  /** Handles PollDoesNotBelongToGroupException and returns 404 Not Found. */
  @ExceptionHandler(PollDoesNotBelongToGroupException.class)
  public ResponseEntity<ApiErrorResponse> handlePollDoesNotBelongToGroup(
      PollDoesNotBelongToGroupException ex) {
    log.warn("Poll does not belong to group", ex);
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  /** Handles PollOptionNotFoundException and returns 404 Not Found. */
  @ExceptionHandler(PollOptionNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handlePollOptionNotFound(PollOptionNotFoundException ex) {
    log.warn("Poll option not found", ex);
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  /** Handles RateLimitExceededException and returns 429 Limit Exceeded. */
  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ApiErrorResponse> handleRateLimitExceededException(
      RateLimitExceededException ex) {
    log.warn("Rate limit exceeded", ex);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
        .body(new ApiErrorResponse("RATE_LIMITED", "Too Many Requests", Instant.now()));
  }

  /** Handles database-related exceptions and returns 500 Internal Server Error. */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ApiErrorResponse> handleDataAccessException(DataAccessException ex) {
    log.error("Database access error", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
  }

  /** Handles any other RuntimeException and returns 500 Internal Server Error. */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ApiErrorResponse> handleRuntimeException(RuntimeException ex) {
    log.error("Unhandled runtime exception", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
  }
}
