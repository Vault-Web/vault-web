package vaultWeb.services;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.repositories.ChatImageRepo;
import vaultWeb.repositories.UserRepository;

@Service
@RequiredArgsConstructor
public class ChatImageService {

  private final ChatImageRepo chatImageRepo;
  private final UserRepository userRepository;

  /**
   * Persists a chat image payload and links it to the given sender and receiver users.
   *
   * <p>Validates that both {@code senderUserId} and {@code receiverUserId} are provided and refer
   * to existing users in the system before storing the image. The image bytes are inserted using a
   * native query optimized for PostgreSQL bytea.
   *
   * @param imageBytes raw image bytes to store (already validated for size/type upstream)
   * @param senderUserId ID of the user sending the image (must not be null; must exist)
   * @param receiverUserId ID of the user receiving the image (must not be null; must exist)
   * @return the generated image record ID
   * @throws IllegalArgumentException if either user ID is null
   * @throws UserNotFoundException if either the sender or receiver cannot be found
   */
  public Long uploadChatImage(byte[] imageBytes, Integer senderUserId, Integer receiverUserId) {
    if (senderUserId == null) {
      throw new IllegalArgumentException("senderUserId must not be null");
    }
    if (receiverUserId == null) {
      throw new IllegalArgumentException("receiverUserId must not be null");
    }

    userRepository
        .findById(Long.valueOf(senderUserId))
        .orElseThrow(
            () -> new UserNotFoundException("Sender with id " + senderUserId + " not found"));
    userRepository
        .findById(Long.valueOf(receiverUserId))
        .orElseThrow(
            () -> new UserNotFoundException("Receiver with id " + receiverUserId + " not found"));

    OffsetDateTime createdon = OffsetDateTime.now();
    Long ref = chatImageRepo.saveImage(imageBytes, senderUserId, receiverUserId, createdon);
    return ref;
  }
}
