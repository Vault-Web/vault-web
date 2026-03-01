package vaultWeb.services;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vaultWeb.dtos.ChatMessageDto;
import vaultWeb.exceptions.DecryptionFailedException;
import vaultWeb.exceptions.EncryptionFailedException;
import vaultWeb.exceptions.notfound.GroupNotFoundException;
import vaultWeb.exceptions.notfound.UserNotFoundException;
import vaultWeb.models.ChatMessage;
import vaultWeb.models.Group;
import vaultWeb.models.PrivateChat;
import vaultWeb.models.User;
import vaultWeb.repositories.ChatMessageRepository;
import vaultWeb.repositories.GroupRepository;
import vaultWeb.repositories.PrivateChatRepository;
import vaultWeb.repositories.UserRepository;
import vaultWeb.security.EncryptionUtil;

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
  public ChatMessage saveMessage(ChatMessageDto dto, boolean contentAlreadyEncrypted) {
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

    String cipherText;
    String iv;

    if (contentAlreadyEncrypted) {
      cipherText = dto.getCipherText();
      iv = dto.getIv();

      if (cipherText == null || cipherText.isBlank() || iv == null || iv.isBlank()) {
        throw new EncryptionFailedException("Missing encrypted payload");
      }
    } else {
      EncryptionUtil.EncryptResult encrypted;
      try {
        encrypted = encryptionUtil.encrypt(dto.getContent());
      } catch (Exception e) {
        throw new EncryptionFailedException("Encryption failed", e);
      }

      cipherText = encrypted.cipherTextBase64;
      iv = encrypted.ivBase64;
    }

    ChatMessage message = new ChatMessage();
    message.setCipherText(cipherText);
    message.setIv(iv);
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


  public ChatMessage saveMessage(ChatMessageDto dto) {
    return saveMessage(dto, false);
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
  }
}
