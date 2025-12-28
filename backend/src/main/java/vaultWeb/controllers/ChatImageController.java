package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vaultWeb.dtos.ChatImageUploadResponse;
import vaultWeb.models.User;
import vaultWeb.services.ChatImageService;
import vaultWeb.services.auth.AuthService;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat Image Controller", description = "Handles image uploads for chat messages")
@RequiredArgsConstructor
@Slf4j
public class ChatImageController {

  private final ChatImageService chatImageService;
  private final AuthService authService;

  @Value("${vault.chatImage.maxSizeBytes:5242880}") // default 5 MB
  private long maxFileSizeBytes;

  @Value("${vault.chatImage.allowedMimeTypes:image/jpeg,image/png,image/gif,image/webp}")
  private String allowedMimeTypesProp;

  /**
   * Handles multipart image uploads for chat messages.
   *
   * <p>Performs basic validation on the uploaded file (non-empty, size limit, allowed MIME types)
   * and delegates to {@link vaultWeb.services.ChatImageService} to persist the image and validate
   * sender/receiver users.
   *
   * @param imageFile the uploaded multipart image file (field name: {@code image}) The sender is
   *     derived from the currently authenticated user; clients must NOT provide it.
   * @param receiverUserId the ID of the receiving user (must not be null)
   * @return a 200 OK response containing a structured JSON body with the new image ID
   * @throws IllegalArgumentException if the file is empty/invalid or if IDs are null
   */
  @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Upload Chat Image",
      description =
          "Uploads an image to be used in chat messages. The sender is the currently authenticated user.")
  public ResponseEntity<ChatImageUploadResponse> uploadChatImage(
      @RequestParam("image") MultipartFile imageFile, @RequestParam Integer receiverUserId) {
    try {
      // Derive sender from the authenticated user
      User currentUser = authService.getCurrentUser();
      if (currentUser == null) {
        return ResponseEntity.status(401)
            .body(new ChatImageUploadResponse("Unauthorized: user is not authenticated", null));
      }
      byte[] imageByteArray = validateAndReadImage(imageFile);
      Long imageId =
          chatImageService.uploadChatImage(
              imageByteArray, currentUser.getId().intValue(), receiverUserId);
      ChatImageUploadResponse body =
          new ChatImageUploadResponse("Image uploaded successfully", imageId);
      return ResponseEntity.ok(body);
    } catch (IOException e) {
      // Log the root cause for diagnostics while returning a stable, user-friendly message
      log.error("Failed to read/process uploaded image file", e);
      return ResponseEntity.status(500)
          .body(new ChatImageUploadResponse("Failed to process image file", null));
    }
  }

  private byte[] validateAndReadImage(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Image file cannot be empty");
    }
    if (file.getSize() > maxFileSizeBytes) {
      throw new IllegalArgumentException("Image file too large");
    }

    // Read the bytes only once and validate using magic bytes (server-side detection)
    byte[] bytes = file.getBytes();

    String detectedMime = detectMimeType(bytes);
    Set<String> allowed = new HashSet<>(Arrays.asList(allowedMimeTypesProp.split(",")));
    if (detectedMime == null || !allowed.contains(detectedMime)) {
      throw new IllegalArgumentException(
          "Unsupported image type. Detected: " + detectedMime + ". Allowed: " + allowed);
    }

    return bytes;
  }

  // Very small, fast signature checks for common image formats
  private String detectMimeType(byte[] bytes) {
    if (bytes == null || bytes.length < 12) {
      return null;
    }

    // JPEG: FF D8 FF
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }

    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if ((bytes[0] & 0xFF) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4E
        && bytes[3] == 0x47
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }

    // GIF: "GIF87a" or "GIF89a"
    if (bytes.length >= 6) {
      String sig = new String(bytes, 0, 6);
      if ("GIF87a".equals(sig) || "GIF89a".equals(sig)) {
        return "image/gif";
      }
    }

    // WEBP: RIFF....WEBP
    if (bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P') {
      return "image/webp";
    }

    return null;
  }
}
