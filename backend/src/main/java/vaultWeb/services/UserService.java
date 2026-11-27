package vaultWeb.services;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vaultWeb.dtos.user.SecurityEventDto;
import vaultWeb.exceptions.DuplicateUsernameException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.SecurityEvent;
import vaultWeb.models.User;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.auth.PersonalAccessTokenService;

/**
 * Service class for managing users.
 *
 * <p>Provides functionality for user registration, checking for existing usernames, and retrieving
 * all users. Passwords are securely encoded before storing.
 */
@Service
@RequiredArgsConstructor
public class UserService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecurityEventRepository securityEventRepository;
  private final PersonalAccessTokenService personalAccessTokenService;

  /**
   * Registers a new user by encoding their password and assigning the default role.
   *
   * <p>Steps performed by this method:
   *
   * <ol>
   *   <li>Check if the username already exists in the database. If so, throw {@link
   *       DuplicateUsernameException}.
   *   <li>Encode the plaintext password using the injected {@link PasswordEncoder}.
   *   <li>Save the user entity with the hashed password to the database via {@link UserRepository}.
   * </ol>
   *
   * <p>Important: - The PasswordEncoder bean must match the encoder used during authentication to
   * correctly verify passwords.
   *
   * @param user The {@link User} entity containing username and plaintext password.
   * @throws DuplicateUsernameException if a user with the same username already exists.
   */
  public void registerUser(User user) {
    if (usernameExists(user.getUsername())) {
      throw new DuplicateUsernameException(
          "Username '" + user.getUsername() + "' is already taken");
    }
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    userRepository.save(user);
  }

  /**
   * Checks if a username already exists in the database.
   *
   * @param username The username to check.
   * @return {@code true} if the username exists, {@code false} otherwise.
   */
  public boolean usernameExists(String username) {
    return userRepository.existsByUsername(username);
  }

  /**
   * Retrieves a list of all registered users.
   *
   * @return A {@link List} of {@link User} entities.
   */
  public List<User> getAllUsers() {
    return userRepository.findAll();
  }

  /**
   * Allows an authenticated user to change their password after validating the old password.
   *
   * @param user The authenticated {@link User} requesting the change.
   * @param currentPassword The plaintext current password provided by the user.
   * @param newPassword The new plaintext password to set.
   * @throws UnauthorizedException if the user is null or the current password is invalid.
   */
  public void changePassword(User user, String currentPassword, String newPassword) {
    if (user == null) {
      throw new UnauthorizedException("No authenticated user found");
    }

    if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
      throw new UnauthorizedException("Current password is incorrect");
    }

    user.setPassword(passwordEncoder.encode(newPassword));
    userRepository.save(user);

    // A password change usually signals suspected compromise: revoke all personal access tokens.
    int revoked = personalAccessTokenService.revokeAllForUser(user);
    if (revoked > 0) {
      logSecurityEvent(user, SecurityEventType.PAT_REVOKED_ALL, "SUCCESS", currentRequest(), null);
    }
  }

  private static HttpServletRequest currentRequest() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? attrs.getRequest()
        : null;
  }

  /**
   * Retrieves recent security events for a specific user, ordered chronologically (newest first).
   */
  public List<SecurityEventDto> getSecurityEvents(User user) {
    return securityEventRepository.findTop50ByUserOrderByTimestampDesc(user).stream()
        .map(SecurityEventDto::new)
        .toList();
  }

  /** Manually logs a security event (e.g. for external/custom actions). */
  public void logSecurityEvent(
      User user,
      SecurityEventType eventType,
      String status,
      HttpServletRequest request,
      String deviceId) {
    SecurityEvent event = new SecurityEvent();
    event.setUser(user);
    event.setUsername(user != null ? user.getUsername() : "anonymous");
    event.setEventType(eventType);
    event.setStatus(status);
    event.setTimestamp(Instant.now());
    event.setDeviceId(deviceId);
    if (request != null) {
      event.setIpAddress(request.getRemoteAddr());
      String ua = request.getHeader("User-Agent");
      event.setUserAgent(ua != null ? ua : "unknown");
    } else {
      event.setIpAddress("unknown");
      event.setUserAgent("unknown");
    }
    event.setLocation("Unknown");
    securityEventRepository.save(event);
  }

  /** Saves a profile picture path to the user's record in the database. */
  public void updateProfilePicture(User user, String picturePath) {
    user.setProfilePicture(picturePath);
    userRepository.save(user);
  }

  /** Clears the profile picture from the user's database record (sets it to null). */
  public void removeProfilePicture(User user) {
    user.setProfilePicture(null);
    userRepository.save(user);
  }
}
