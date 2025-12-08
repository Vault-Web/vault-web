package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vaultWeb.dtos.user.UserDto;
import vaultWeb.dtos.user.UserResponseDto;
import vaultWeb.models.ChatImage;
import vaultWeb.models.User;
import vaultWeb.services.ChatService;
import vaultWeb.services.UserService;
import vaultWeb.services.auth.AuthService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "User Controller", description = "Handles registration and login of users")
@RequiredArgsConstructor
public class UserController {
    private  final ChatService chatService;
    private final UserService userService;
    private final AuthService authService;

  @PostMapping("/register")
  @Operation(
      summary = "Register a new user",
      description =
          """
                    Accepts a JSON object containing username and plaintext password.
                    The password is hashed using BCrypt (via Spring Security's PasswordEncoder) before being persisted.
                    The new user is assigned the default role 'User'.""")
  public ResponseEntity<String> register(@RequestBody UserDto user) {
    userService.registerUser(new User(user));
    return ResponseEntity.ok("User registered successfully");
  }

  @PostMapping("/login")
  @Operation(
      summary = "Authenticate user and return JWT token",
      description =
          """
                    Accepts a username and plaintext password.
                    If credentials are valid, a JWT (JSON Web Token) is returned in the response body.
                    The token includes the username and user role as claims and is signed using HS256 (HMAC with SHA-256).
                    Token validity is 1 hour.

                    Security process:
                    - Uses Spring Security's AuthenticationManager to validate credentials.
                    - On success, the user details are fetched and a JWT is generated via JwtUtil.
                    - The token can be used in the 'Authorization' header for protected endpoints.
                    """)
  public ResponseEntity<?> login(@RequestBody UserDto user) {
    String token = authService.login(user.getUsername(), user.getPassword());
    return ResponseEntity.ok(Map.of("token", token));
  }

  @GetMapping("/check-username")
  @Operation(
      summary = "Check if username already exists",
      description = "Returns true if the username is already taken, false otherwise.")
  public ResponseEntity<Map<String, Boolean>> checkUsernameExists(@RequestParam String username) {
    boolean exists = userService.usernameExists(username);
    return ResponseEntity.ok(Map.of("exists", exists));
  }

    @GetMapping("/users")
    @Operation(
            summary = "Get all users",
            description = "Returns a list of all users with basic info (e.g., usernames) for displaying in the chat list."
    )
    public ResponseEntity<List<UserResponseDto>> getAllUsers() {
        List<UserResponseDto> users = userService.getAllUsers()
                .stream()
                .map(UserResponseDto::new)
                .toList();
        return ResponseEntity.ok(users);
    }

@PostMapping("/chatImageUpload")
@Operation(
        summary = "Upload chat image",
        description = "Accepts a multipart/form-data image and saves it as a chat image between the sender and receiver."
)
public ResponseEntity<String> uploadChatImage(@RequestPart("image") MultipartFile image,
                                              Principal principal,
                                              @RequestParam int sender_id, @RequestParam int receiver_id) throws java.io.IOException {
    if (image == null || image.isEmpty()) {
        return ResponseEntity.badRequest().body("No image provided");
    }
    try {
        long id = chatService.uploadImage(image, receiver_id, sender_id);
        return ResponseEntity.ok("Image uploaded successfully with ID " + id);
    }
    catch (Exception e) {
            return ResponseEntity.status(500).body("Error uploading image: " + e.getMessage());
        }
    }

    @GetMapping("/chat-images/{id}")
    @Operation(
            summary = "Fetch a chat image",
            description = "Returns the stored chat image bytes for the provided ID."
    )
    public ResponseEntity<byte[]> getChatImage(@Parameter @PathVariable Long id) {
        ChatImage chatImage = chatService.getImage(id);

        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.hasText(chatImage.getContentType())) {
            headers.setContentType(MediaType.parseMediaType(chatImage.getContentType()));
        } else {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        if (chatImage.getImageContent() != null) {
            headers.setContentLength(chatImage.getImageContent().length);
        }
        if (StringUtils.hasText(chatImage.getFileName())) {
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(chatImage.getFileName())
                    .build());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(chatImage.getImageContent());
    }

  @GetMapping("/users")
  @Operation(
      summary = "Get all users",
      description =
          "Returns a list of all users with basic info (e.g., usernames) for displaying in the chat list.")
  public ResponseEntity<List<UserResponseDto>> getAllUsers() {
    List<UserResponseDto> users =
        userService.getAllUsers().stream().map(UserResponseDto::new).toList();
    return ResponseEntity.ok(users);
  }
}
