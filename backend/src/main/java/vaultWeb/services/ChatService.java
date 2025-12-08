package vaultWeb.services;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.DecryptionFailedException;
import vaultWeb.exceptions.EncryptionFailedException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.*;
import vaultWeb.repositories.*;
import vaultWeb.security.EncryptionUtil;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Service responsible for handling chat-related operations.
 *
 * <p>This service provides methods to save and decrypt chat messages for both group chats and
 * private chats. Messages are encrypted before being stored in the database to ensure
 * confidentiality. The service supports identifying the sender either by ID or username and can
 * handle automatic timestamping if none is provided.
 *
 * <p>Main responsibilities:
 *
 * <ul>
 *   <li>Validate sender existence by ID or username.
 *   <li>Encrypt message content before saving.
 *   <li>Associate messages with a group or private chat.
 *   <li>Persist chat messages into the database.
 *   <li>Decrypt stored messages on demand.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final PrivateChatRepository privateChatRepository;
    private final EncryptionUtil encryptionUtil;
    private final ChatImageRepo chatImageRepo;

    /**
     * Saves a chat message to a group or private chat.
     *
     * <p>The message content is encrypted before being persisted. The sender is
     * identified either by ID or username. If a timestamp is not provided, the
     * current time is used. The message must belong to either a group or a private chat.</p>
     *
     * @param dto DTO containing the message content, sender information, timestamp,
     *            and either a groupId or privateChatId.
     * @return The persisted ChatMessage entity with encrypted content.
     * @throws UserNotFoundException  if the sender cannot be found by ID or username.
     * @throws GroupNotFoundException if neither groupId nor privateChatId is provided,
     *                                or if the specified group/private chat does not exist.
     * @throws RuntimeException       if encryption fails.
     */
    public ChatMessage saveMessage(ChatMessageDto dto) {
        User sender;

  /**
   * Saves a chat message to a group or private chat.
   *
   * <p>The message content is encrypted before being persisted. The sender is identified either by
   * ID or username. If a timestamp is not provided, the current time is used. The message must
   * belong to either a group or a private chat.
   *
   * @param dto DTO containing the message content, sender information, timestamp, and either a
   *     groupId or privateChatId.
   * @return The persisted ChatMessage entity with encrypted content.
   * @throws UserNotFoundException if the sender cannot be found by ID or username.
   * @throws GroupNotFoundException if neither groupId nor privateChatId is provided, or if the
   *     specified group/private chat does not exist.
   * @throws EncryptionFailedException if encryption fails.
   */
  public ChatMessage saveMessage(ChatMessageDto dto) {
    User sender;

    if (dto.getSenderId() != null) {
      sender =
          userRepository
              .findById(dto.getSenderId())
              .orElseThrow(() -> new UserNotFoundException("Sender not found by ID"));
    } else if (dto.getSenderUsername() != null) {
      sender =
          userRepository
              .findByUsername(dto.getSenderUsername())
              .orElseThrow(() -> new UserNotFoundException("Sender not found by username"));
    } else {
      throw new UserNotFoundException("Sender information missing");
    }

    EncryptionUtil.EncryptResult encrypted;
    try {
      encrypted = encryptionUtil.encrypt(dto.getContent());
    } catch (Exception e) {
      throw new EncryptionFailedException("Encryption failed", e);
    }

    ChatMessage message = new ChatMessage();
    message.setCipherText(encrypted.cipherTextBase64);
    message.setIv(encrypted.ivBase64);
    message.setSender(sender);

    if (dto.getTimestamp() != null) {
      message.setTimestamp(Instant.parse(dto.getTimestamp()));
    } else {
      message.setTimestamp(Instant.now());
    }

    if (dto.getGroupId() != null) {
      Group group =
          groupRepository
              .findById(dto.getGroupId())
              .orElseThrow(
                  () ->
                      new GroupNotFoundException(
                          "Group with id " + dto.getGroupId() + " not found"));
      message.setGroup(group);
    } else if (dto.getPrivateChatId() != null) {
      PrivateChat privateChat =
          privateChatRepository
              .findById(dto.getPrivateChatId())
              .orElseThrow(() -> new GroupNotFoundException("PrivateChat not found"));
      message.setPrivateChat(privateChat);
    } else {
      throw new GroupNotFoundException("Either groupId or privateChatId must be provided");
    }

    return chatMessageRepository.save(message);
  }

  /**
   * Decrypts a previously encrypted chat message.
   *
   * <p>Uses the IV and cipher text stored in the database to decrypt and return the original
   * message content as a plain string.
   *
   * @param cipherTextBase64 The encrypted message in Base64 encoding.
   * @param ivBase64 The initialization vector used during encryption, in Base64.
   * @return The decrypted plain text message.
   * @throws DecryptionFailedException if decryption fails.
   */
  public String decrypt(String cipherTextBase64, String ivBase64) {
    try {
      return encryptionUtil.decrypt(cipherTextBase64, ivBase64);
    } catch (Exception e) {
      throw new DecryptionFailedException("Decryption failed", e);
    }

    public long uploadImage(MultipartFile image, ChatImage body, int receiver_id, int sender_id) throws IOException {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Image must not be empty");
        }

        ChatImage imageEntity = mapImagePayload(image, body, receiver_id, sender_id);
        ChatImage saved = chatImageRepo.save(imageEntity);
        return saved.getId();
    }

    public ChatImage getImage(Long id) {
        return chatImageRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chat image not found with id " + id));
    }

    private ChatImage mapImagePayload(MultipartFile image, ChatImage body, int receiver_id, int sender_id) throws IOException {
        ChatImage result = body != null ? body : new ChatImage();

        if (!StringUtils.hasText(result.getFileName())) {
            result.setFileName(image.getOriginalFilename());
        }
        if (!StringUtils.hasText(result.getContentType())) {
            result.setContentType(image.getContentType());
        }
        if (result.getCreatedOn() == null) {
            result.setCreatedOn(OffsetDateTime.now());
        }

        // Ensure sender and receiver IDs are present on the entity
        if (result.getSenderId() == null) {
            result.setSenderId((long) sender_id);
        }
        if (result.getReceiverId() == null) {
            result.setReceiverId((long) receiver_id);
        }

        result.setImageContent(image.getBytes());
        return result;
    }
}
  }
}
