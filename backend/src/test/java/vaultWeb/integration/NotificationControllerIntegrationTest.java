package vaultWeb.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vaultWeb.models.Notification;
import vaultWeb.models.User;
import vaultWeb.security.JwtUtil;

class NotificationControllerIntegrationTest extends IntegrationTestBase {
  @Autowired private JwtUtil jwtUtil;

  private User createUser(String username) {
    User user = new User();
    user.setUsername(username);
    user.setPassword("hashed-password");
    return userRepository.save(user);
  }

  private String token(User user) {
    return "Bearer " + jwtUtil.generateToken(user);
  }

  private Notification createNotification(User user) {
    Notification notification = new Notification();
    notification.setUser(user);
    notification.setSource("SECURITY");
    notification.setType("LOGIN");
    notification.setTitle("New login");
    notification.setMessage("A login to your account was recorded.");
    notification.setLinkUrl("/security-activity");
    notification.setCreatedAt(Instant.now());
    return notificationRepository.save(notification);
  }

  @Test
  void inboxRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
  }

  @Test
  void usersOnlyReceiveTheirOwnNotificationsAndUnreadCount() throws Exception {
    User alice = createUser("alice");
    User bob = createUser("bob");
    createNotification(alice);
    Notification bobsItem = createNotification(bob);

    mockMvc.perform(get("/api/notifications").header("Authorization", token(alice)))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].title").value("New login"));

    mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", token(alice)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));

    mockMvc.perform(patch("/api/notifications/" + bobsItem.getId() + "/read")
            .header("Authorization", token(alice))
            .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void markAllReadOnlyUpdatesCurrentUsersItems() throws Exception {
    User alice = createUser("alice");
    User bob = createUser("bob");
    createNotification(alice);
    createNotification(bob);

    mockMvc.perform(post("/api/notifications/mark-all-read")
            .header("Authorization", token(alice)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));

    mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", token(alice)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", token(bob)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
  }

  @Test
  void nullReadValueReturnsBadRequest() throws Exception {
    User alice = createUser("alice");
    Notification item = createNotification(alice);

    mockMvc
        .perform(
            patch("/api/notifications/" + item.getId() + "/read")
                .header("Authorization", token(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"read\":null}"))
        .andExpect(status().isBadRequest());
  }

}
